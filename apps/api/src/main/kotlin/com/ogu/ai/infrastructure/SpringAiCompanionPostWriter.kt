package com.ogu.ai.infrastructure

import com.ogu.ai.CompanionPostFailed
import com.ogu.ai.CompanionPostInput
import com.ogu.ai.CompanionPostWriter
import com.ogu.ai.EmotionAnalysisFailed.Kind
import com.ogu.shared.config.AiProperties
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.timelimiter.TimeLimiter
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.core.io.ClassPathResource
import java.io.InterruptedIOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

/**
 * OpenAI 호환 엔드포인트로 AI 친구의 고민 글을 쓴다(012 research R15). 댓글 쓰기와 같은 공급자와 설정(`ogu.ai.*`)을 쓰되,
 * 서킷 브레이커와 타임아웃 인스턴스(`companionPost`)는 따로 둔다. 글쓰기가 흔들려도 댓글 쓰기의 회로는 열리지 않는다.
 *
 * - 시스템 프롬프트는 `prompts/companion-post-v1.st`, 사용자 메시지는 [CompanionPostInput]의 세 가지를 적은 세 줄이다.
 *   그 밖의 것은 보내지 않는다(FR-027). 회원의 글이 들어갈 자리가 없다.
 * - 재시도는 companion 모듈의 일정(companion_post.next_attempt_at)에 맡기므로 SDK의 자체 재시도는 끈다.
 * - 모델의 답은 로그에 남기지 않는다. 실패는 분류만 남긴다.
 */
class SpringAiCompanionPostWriter(
    private val chatClient: ChatClient,
    private val systemPrompt: String,
    private val circuitBreaker: CircuitBreaker,
    private val timeLimiter: TimeLimiter,
) : CompanionPostWriter,
    AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    override fun write(
        key: String,
        input: CompanionPostInput,
    ): String {
        val raw = callModel(key, input)
        return try {
            CompanionPostValidator.validated(raw)
        } catch (e: CompanionPostFailed) {
            log.warn("AI 친구의 글이 규칙에 맞지 않습니다: target={}, kind={}", key, e.kind)
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught") // SDK가 던지는 모든 예외를 실패 분류로 바꿔 재시도 일정에 맡긴다
    private fun callModel(
        key: String,
        input: CompanionPostInput,
    ): String? =
        try {
            circuitBreaker.executeCallable {
                timeLimiter.executeFutureSupplier {
                    CompletableFuture.supplyAsync({ request(input) }, executor)
                }
            }
        } catch (e: CallNotPermittedException) {
            fail(key, Kind.CIRCUIT_OPEN, e)
        } catch (e: TimeoutException) {
            fail(key, Kind.TIMEOUT, e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(key, Kind.UPSTREAM_ERROR, e)
        } catch (e: Exception) {
            fail(key, if (isTimeout(e)) Kind.TIMEOUT else Kind.UPSTREAM_ERROR, e)
        }

    private fun isTimeout(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { it is InterruptedIOException || it is HttpTimeoutException }

    private fun request(input: CompanionPostInput): String? =
        chatClient
            .prompt(Prompt(listOf(SystemMessage(systemPrompt), UserMessage(userMessage(input)))))
            .call()
            .content()

    private fun fail(
        key: String,
        kind: Kind,
        cause: Exception,
    ): Nothing {
        // 원인 예외의 메시지에 공급자 응답 일부가 들어갈 수 있어 메시지 없이 예외 종류만 남긴다.
        log.warn("AI 친구의 글쓰기 호출 실패: target={}, kind={}, cause={}", key, kind, cause.javaClass.simpleName)
        throw CompanionPostFailed(kind)
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        /** Resilience4j 서킷 브레이커와 타임아웃 인스턴스 이름(`resilience4j.*.instances.companionPost`). */
        const val RESILIENCE_NAME = "companionPost"
        private const val MAX_CAUSE_DEPTH = 10
        private const val PROMPT = "prompts/companion-post-v1.st"

        /** 공급자에 보내는 사용자 메시지. 소재, 말씨, 댓글 말투뿐이다. 소재는 코드에 정해 둔 목록의 한 줄이다. */
        fun userMessage(input: CompanionPostInput): String =
            listOf(
                "소재: ${input.topic}",
                "말씨: ${input.voice.label}",
                "댓글 말투: ${input.tone.label}",
            ).joinToString("\n")

        fun create(
            properties: AiProperties,
            circuitBreaker: CircuitBreaker,
            timeLimiter: TimeLimiter,
        ): SpringAiCompanionPostWriter {
            require(properties.apiKey.isNotBlank()) { "키가 없으면 FakeCompanionPostWriter.Disabled를 씁니다." }
            val options =
                OpenAiChatOptions
                    .builder()
                    .baseUrl(properties.baseUrl.toString())
                    .apiKey(properties.apiKey)
                    .model(properties.model)
                    // 글을 쓰는 일이다. 낮으면 같은 소재에 같은 글이 온다
                    .temperature(properties.companionTemperature)
                    .maxTokens(properties.companionPostMaxTokens)
                    .timeout(properties.timeout)
                    .maxRetries(0)
                    .withReasoningEffort(properties.reasoningEffort)
                    .build()
            val chatModel = OpenAiChatModel.builder().options(options).build()
            return SpringAiCompanionPostWriter(
                chatClient = ChatClient.create(chatModel),
                systemPrompt = ClassPathResource(PROMPT).getContentAsString(Charsets.UTF_8).trim(),
                circuitBreaker = circuitBreaker,
                timeLimiter = timeLimiter,
            )
        }
    }
}
