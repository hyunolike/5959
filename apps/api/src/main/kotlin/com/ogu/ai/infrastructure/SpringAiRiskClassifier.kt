package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedRisk
import com.ogu.ai.EmotionAnalysisFailed.Kind
import com.ogu.ai.RiskClassificationFailed
import com.ogu.ai.RiskClassifier
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
 * OpenAI 호환 엔드포인트로 위험 단계를 분류한다(005 research R3). 감정 분석과 같은 공급자와 설정(`ogu.ai.*`)을 쓰되,
 * 서킷 브레이커와 타임아웃 인스턴스(`riskClassifier`)는 따로 둔다. 한쪽 프롬프트의 실패가 다른 쪽을 열지 않게 한다.
 *
 * - 시스템 프롬프트는 `prompts/risk-classification-v1.st`, 본문은 사용자 메시지로 그대로 보낸다.
 * - 재시도는 safety 모듈의 일정(risk_assessment.next_attempt_at)에 맡기므로 SDK의 자체 재시도는 끈다.
 * - 모델 응답 원문과 본문은 로그에 남기지 않는다. 실패는 분류만 남긴다.
 */
class SpringAiRiskClassifier(
    private val chatClient: ChatClient,
    private val systemPrompt: String,
    private val circuitBreaker: CircuitBreaker,
    private val timeLimiter: TimeLimiter,
) : RiskClassifier,
    AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    override fun classify(
        key: String,
        content: String,
    ): ClassifiedRisk {
        val raw = callModel(key, content)
        return try {
            RiskResponseParser.parse(raw)
        } catch (e: RiskClassificationFailed) {
            log.warn("위험 분류 응답을 해석하지 못했습니다: target={}, kind={}", key, e.kind)
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught") // SDK가 던지는 모든 예외를 실패 분류로 바꿔 재시도 일정에 맡긴다
    private fun callModel(
        key: String,
        content: String,
    ): String? =
        try {
            circuitBreaker.executeCallable {
                timeLimiter.executeFutureSupplier {
                    CompletableFuture.supplyAsync({ request(content) }, executor)
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

    private fun request(content: String): String? =
        chatClient
            .prompt(Prompt(listOf(SystemMessage(systemPrompt), UserMessage(content))))
            .call()
            .content()

    private fun fail(
        key: String,
        kind: Kind,
        cause: Exception,
    ): Nothing {
        // 원인 예외의 메시지에 공급자 응답 일부가 들어갈 수 있어 메시지 없이 예외 종류만 남긴다.
        log.warn("위험 분류 호출 실패: target={}, kind={}, cause={}", key, kind, cause.javaClass.simpleName)
        throw RiskClassificationFailed(kind)
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        /** Resilience4j 서킷 브레이커와 타임아웃 인스턴스 이름(`resilience4j.*.instances.riskClassifier`). */
        const val RESILIENCE_NAME = "riskClassifier"
        private const val MAX_CAUSE_DEPTH = 10
        private const val PROMPT = "prompts/risk-classification-v1.st"

        /** 응답은 `{"level":"CONCERN"}` 한 줄이라 토큰이 적게 든다. */
        private const val MAX_TOKENS = 30

        fun create(
            properties: AiProperties,
            circuitBreaker: CircuitBreaker,
            timeLimiter: TimeLimiter,
        ): SpringAiRiskClassifier {
            require(properties.apiKey.isNotBlank()) { "키가 없으면 DisabledRiskClassifier를 씁니다." }
            val options =
                OpenAiChatOptions
                    .builder()
                    .baseUrl(properties.baseUrl.toString())
                    .apiKey(properties.apiKey)
                    .model(properties.model)
                    .temperature(0.0)
                    .maxTokens(MAX_TOKENS)
                    .timeout(properties.timeout)
                    .maxRetries(0)
                    .build()
            val chatModel = OpenAiChatModel.builder().options(options).build()
            return SpringAiRiskClassifier(
                chatClient = ChatClient.create(chatModel),
                systemPrompt = ClassPathResource(PROMPT).getContentAsString(Charsets.UTF_8).trim(),
                circuitBreaker = circuitBreaker,
                timeLimiter = timeLimiter,
            )
        }
    }
}
