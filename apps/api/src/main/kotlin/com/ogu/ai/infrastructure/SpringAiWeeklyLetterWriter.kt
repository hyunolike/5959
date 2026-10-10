package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.EmotionAnalysisFailed.Kind
import com.ogu.ai.WeeklyLetterFailed
import com.ogu.ai.WeeklyLetterInput
import com.ogu.ai.WeeklyLetterWriter
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
 * OpenAI 호환 엔드포인트로 주간 리포트의 편지를 쓴다(008 research R7). 감정 분석과 같은 공급자와 설정(`ogu.ai.*`)을
 * 쓰되, 서킷 브레이커와 타임아웃 인스턴스(`weeklyLetter`)는 따로 둔다.
 *
 * - 시스템 프롬프트는 `prompts/weekly-letter-v1.st`, 사용자 메시지는 [WeeklyLetterInput]의 수치를 적은 몇 줄이다.
 *   그 밖의 것은 보내지 않는다(FR-010).
 * - 재시도는 report 모듈의 일정(weekly_report.letter_next_attempt_at)에 맡기므로 SDK의 자체 재시도는 끈다.
 * - 모델의 답과 수치는 로그에 남기지 않는다. 실패는 분류만 남긴다.
 */
class SpringAiWeeklyLetterWriter(
    private val chatClient: ChatClient,
    private val systemPrompt: String,
    private val circuitBreaker: CircuitBreaker,
    private val timeLimiter: TimeLimiter,
    private val maxLength: Int,
) : WeeklyLetterWriter,
    AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    override fun write(
        key: String,
        input: WeeklyLetterInput,
    ): String {
        val raw = callModel(key, input)
        return try {
            WeeklyLetterValidator.validated(raw, input, maxLength)
        } catch (e: WeeklyLetterFailed) {
            log.warn("편지가 규칙에 맞지 않습니다: target={}, kind={}", key, e.kind)
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught") // SDK가 던지는 모든 예외를 실패 분류로 바꿔 재시도 일정에 맡긴다
    private fun callModel(
        key: String,
        input: WeeklyLetterInput,
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

    private fun request(input: WeeklyLetterInput): String? =
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
        log.warn("편지 쓰기 호출 실패: target={}, kind={}, cause={}", key, kind, cause.javaClass.simpleName)
        throw WeeklyLetterFailed(kind)
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        /** Resilience4j 서킷 브레이커와 타임아웃 인스턴스 이름(`resilience4j.*.instances.weeklyLetter`). */
        const val RESILIENCE_NAME = "weeklyLetter"
        private const val MAX_CAUSE_DEPTH = 10
        private const val PROMPT = "prompts/weekly-letter-v1.st"
        private const val LETTER_TEMPERATURE = 0.7
        private val LABELS =
            mapOf(
                ClassifiedEmotion.ANXIETY to "불안",
                ClassifiedEmotion.LETHARGY to "무기력",
                ClassifiedEmotion.LONELINESS to "외로움",
                ClassifiedEmotion.SELF_DEPRECATION to "자기비하",
                ClassifiedEmotion.IRRITATION to "짜증",
            )

        /** 공급자에 보내는 사용자 메시지. 수치와 감정 이름뿐이다. */
        fun userMessage(input: WeeklyLetterInput): String =
            buildList {
                add("쓴 글 수: ${input.postCount}")
                val emotions =
                    ClassifiedEmotion.entries
                        .filter { (input.emotionCounts[it] ?: 0) > 0 }
                        .joinToString(", ") { "${LABELS.getValue(it)} ${input.emotionCounts.getValue(it)}" }
                add("감정별 글 수: ${emotions.ifEmpty { "분류된 글 없음" }}")
                add("가장 많은 감정: ${input.topEmotion?.let(LABELS::getValue) ?: "없음"}")
                add("처치된 몬스터 수: ${input.defeatedCount}")
                add("받은 공감 수: ${input.receivedLikes}")
                add("받은 댓글 수: ${input.receivedComments}")
                input.previousPostCount?.let { add("앞 주의 글 수: $it") }
                input.previousTopEmotion?.let { add("앞 주의 가장 많은 감정: ${LABELS.getValue(it)}") }
            }.joinToString("\n")

        fun create(
            properties: AiProperties,
            circuitBreaker: CircuitBreaker,
            timeLimiter: TimeLimiter,
            maxLength: Int,
        ): SpringAiWeeklyLetterWriter {
            require(properties.apiKey.isNotBlank()) { "키가 없으면 FakeWeeklyLetterWriter.Disabled를 씁니다." }
            val options =
                OpenAiChatOptions
                    .builder()
                    .baseUrl(properties.baseUrl.toString())
                    .apiKey(properties.apiKey)
                    .model(properties.model)
                    // 분류와 달리 글을 쓰는 일이다. 낮으면 다시 시도해도 같은 답이 온다
                    .temperature(LETTER_TEMPERATURE)
                    .maxTokens(properties.letterMaxTokens)
                    .timeout(properties.timeout)
                    .maxRetries(0)
                    .withReasoningEffort(properties.reasoningEffort)
                    .build()
            val chatModel = OpenAiChatModel.builder().options(options).build()
            return SpringAiWeeklyLetterWriter(
                chatClient = ChatClient.create(chatModel),
                systemPrompt = ClassPathResource(PROMPT).getContentAsString(Charsets.UTF_8).trim(),
                circuitBreaker = circuitBreaker,
                timeLimiter = timeLimiter,
                maxLength = maxLength,
            )
        }
    }
}
