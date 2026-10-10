package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.shared.config.AiProperties
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Duration

/**
 * 실제 공급자와 기본 모델로 감정 분석이 답을 주는지 본다. 모델이나 토큰 상한을 바꿨을 때 돌린다. 공급자가 모델을
 * 내리거나 응답 형식이 달라지면 가짜 분석기를 쓰는 다른 테스트는 알아채지 못한다.
 *
 * ```
 * OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*EmotionAnalyzerLiveTest*" -i | grep LIVE
 * ```
 */
@EnabledIfEnvironmentVariable(named = "OGU_RUN_AI_EVAL", matches = "true")
class EmotionAnalyzerLiveTest {
    @Test
    fun `기본 모델이 감정과 강도, 이유를 돌려준다`() {
        val properties = AiProperties(apiKey = System.getenv("AI_API_KEY").orEmpty())
        val limiter = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(properties.timeout).build())
        val analyzer = SpringAiEmotionAnalyzer.create(properties, CircuitBreaker.ofDefaults("emotionLive"), limiter)

        val results =
            SENTENCES.mapIndexed { index, (sentence, expected) ->
                val started = System.nanoTime()
                val result =
                    try {
                        analyzer.analyze(index.toLong(), sentence)
                    } catch (e: EmotionAnalysisFailed) {
                        println("LIVE emotion failed: ${e.kind} for sentence $index")
                        null
                    }
                val millis = Duration.ofNanos(System.nanoTime() - started).toMillis()
                println("LIVE emotion: $expected → ${result?.emotion}/${result?.intensity} in ${millis}ms")
                Thread.sleep(PACE_MILLIS)
                expected to result
            }

        // 모두 답을 받아야 한다. 답이 비면 토큰 상한이 추론에 다 쓰인 것이다
        assertThat(results.map { it.second }).doesNotContainNull()
        assertThat(results.map { it.second!!.reason }).allSatisfy { assertThat(it).isNotBlank() }
        val matched = results.count { (expected, result) -> result!!.emotion == expected }
        println("LIVE emotion: model ${properties.model}, matched $matched/${results.size}")
        assertThat(matched).isGreaterThanOrEqualTo(MIN_MATCHED)
    }

    private companion object {
        // 검증용으로 지어낸 문장이다
        val SENTENCES =
            listOf(
                "내일 발표를 망칠까 봐 잠이 안 오고 심장이 계속 뛰어요" to ClassifiedEmotion.ANXIETY,
                "아무것도 하기 싫고 출근해도 멍하니 앉아만 있어요" to ClassifiedEmotion.LETHARGY,
                "팀에서 저만 점심에 안 불러요. 혼자 밥 먹는 게 서러워요" to ClassifiedEmotion.LONELINESS,
                "또 실수했어요. 저는 왜 이것밖에 안 되는 사람일까요" to ClassifiedEmotion.SELF_DEPRECATION,
                "퇴근 직전에 일을 던지는 팀장 때문에 화가 치밀어요" to ClassifiedEmotion.IRRITATION,
            )
        const val MIN_MATCHED = 4
        const val PACE_MILLIS = 500L
    }
}
