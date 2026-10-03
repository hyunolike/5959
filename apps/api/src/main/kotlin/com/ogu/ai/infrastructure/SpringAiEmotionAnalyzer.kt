package com.ogu.ai.infrastructure

import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.EmotionAnalyzer
import com.ogu.ai.EmotionClassification
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

/**
 * OpenAI 호환 엔드포인트로 감정을 분류한다(research R3). 설정은 `ogu.ai.*`([AiProperties])에서 읽는다.
 *
 * - 시스템 프롬프트는 `prompts/emotion-analysis-{promptVersion}.st`, 글 본문은 사용자 메시지로 그대로 보낸다.
 *   본문을 템플릿에 끼워 넣지 않으므로 본문의 중괄호가 템플릿 문법으로 해석되지 않는다.
 * - 호출마다 Resilience4j 타임아웃(`ogu.ai.timeout`, 기본 20초)을 걸고, 서킷 브레이커 `emotionAnalyzer`를 거친다.
 *   서킷이 열려 있으면 호출하지 않고 바로 [EmotionAnalysisFailed.Kind.CIRCUIT_OPEN]으로 실패한다. 재시도는 emotion 모듈의
 *   일정(emotion_analysis.next_attempt_at)에 맡기므로 SDK의 자체 재시도는 끈다.
 * - 모델 응답 원문은 로그에 남기지 않는다. 실패는 분류([EmotionAnalysisFailed.Kind])만 남긴다.
 */
class SpringAiEmotionAnalyzer(
    private val chatClient: ChatClient,
    private val systemPrompt: String,
    private val circuitBreaker: CircuitBreaker,
    private val timeLimiter: TimeLimiter,
) : EmotionAnalyzer,
    AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)

    // 타임아웃이 나면 호출을 기다리지 않고 돌아온다. 남은 HTTP 호출은 SDK 타임아웃(같은 값)에 끝난다.
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    override fun analyze(
        postId: Long,
        content: String,
    ): EmotionClassification {
        val raw = callModel(postId, content)
        val classification =
            try {
                EmotionResponseParser.parse(raw)
            } catch (e: EmotionAnalysisFailed) {
                log.warn("감정 분석 응답을 해석하지 못했습니다: postId={}, kind={}", postId, e.kind)
                throw e
            }
        log.info(
            "감정 분석 완료: postId={}, emotion={}, intensity={}",
            postId,
            classification.emotion,
            classification.intensity,
        )
        return classification
    }

    @Suppress("TooGenericExceptionCaught") // SDK가 던지는 모든 예외를 실패 분류로 바꿔 재시도 일정에 맡긴다
    private fun callModel(
        postId: Long,
        content: String,
    ): String? =
        try {
            circuitBreaker.executeCallable {
                timeLimiter.executeFutureSupplier {
                    CompletableFuture.supplyAsync({ request(content) }, executor)
                }
            }
        } catch (e: CallNotPermittedException) {
            fail(postId, EmotionAnalysisFailed.Kind.CIRCUIT_OPEN, e)
        } catch (e: TimeoutException) {
            fail(postId, EmotionAnalysisFailed.Kind.TIMEOUT, e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(postId, EmotionAnalysisFailed.Kind.UPSTREAM_ERROR, e)
        } catch (e: Exception) {
            fail(postId, EmotionAnalysisFailed.Kind.UPSTREAM_ERROR, e)
        }

    private fun request(content: String): String? =
        chatClient
            .prompt(Prompt(listOf(SystemMessage(systemPrompt), UserMessage(content))))
            .call()
            .content()

    private fun fail(
        postId: Long,
        kind: EmotionAnalysisFailed.Kind,
        cause: Exception,
    ): Nothing {
        // 원인 예외의 메시지에 공급자 응답 일부가 들어갈 수 있어 메시지 없이 예외 종류만 남긴다.
        log.warn("감정 분석 호출 실패: postId={}, kind={}, cause={}", postId, kind, cause.javaClass.simpleName)
        throw EmotionAnalysisFailed(kind)
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        /** Resilience4j 서킷 브레이커와 타임아웃 인스턴스 이름(`resilience4j.*.instances.emotionAnalyzer`). */
        const val RESILIENCE_NAME = "emotionAnalyzer"

        /** 키가 비어 있으면 SDK가 환경 변수를 찾다가 실패하므로, 로컬과 테스트에서는 자리표시 값으로 만든다(prod는 기동 검사). */
        private const val MISSING_API_KEY = "missing-ai-api-key"

        fun create(
            properties: AiProperties,
            circuitBreaker: CircuitBreaker,
            timeLimiter: TimeLimiter,
        ): SpringAiEmotionAnalyzer {
            val options =
                OpenAiChatOptions
                    .builder()
                    .baseUrl(properties.baseUrl.toString())
                    .apiKey(properties.apiKey.ifBlank { MISSING_API_KEY })
                    .model(properties.model)
                    .temperature(properties.temperature)
                    .maxTokens(properties.maxTokens)
                    .timeout(properties.timeout)
                    .maxRetries(0)
                    .build()
            val chatModel = OpenAiChatModel.builder().options(options).build()
            return SpringAiEmotionAnalyzer(
                chatClient = ChatClient.create(chatModel),
                systemPrompt = PromptLoader.load(properties.promptVersion),
                circuitBreaker = circuitBreaker,
                timeLimiter = timeLimiter,
            )
        }
    }
}
