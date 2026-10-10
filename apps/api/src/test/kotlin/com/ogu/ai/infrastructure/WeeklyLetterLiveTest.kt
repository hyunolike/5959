package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.WeeklyLetterFailed
import com.ogu.ai.WeeklyLetterInput
import com.ogu.shared.config.AiProperties
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Duration

/**
 * 실제 공급자와 기본 모델이 쓴 편지를 출력한다(008 research R7). 편지의 품질은 수치로 재지 않는다. 사람이 읽고 프롬프트를
 * 다듬는다. 모델이나 프롬프트를 바꿨을 때 돌린다.
 *
 * ```
 * OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*WeeklyLetterLiveTest*" -i | grep LIVE
 * ```
 */
@EnabledIfEnvironmentVariable(named = "OGU_RUN_AI_EVAL", matches = "true")
class WeeklyLetterLiveTest {
    @Test
    fun `기본 모델이 수치 묶음 다섯 개로 규칙에 맞는 편지를 쓴다`() {
        val properties = AiProperties(apiKey = System.getenv("AI_API_KEY").orEmpty())
        val limiter = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(properties.timeout).build())
        val breaker = CircuitBreaker.ofDefaults("letterLive")
        val writer = SpringAiWeeklyLetterWriter.create(properties, breaker, limiter, 300)

        val letters =
            INPUTS.mapIndexed { index, input ->
                val started = System.nanoTime()
                val letter =
                    try {
                        writer.write("LIVE:$index", input)
                    } catch (e: WeeklyLetterFailed) {
                        println("LIVE letter $index failed: ${e.kind}")
                        null
                    }
                val millis = Duration.ofNanos(System.nanoTime() - started).toMillis()
                println("LIVE letter $index (${millis}ms, ${letter?.length}자): $letter")
                Thread.sleep(PACE_MILLIS)
                letter
            }

        println("LIVE letter: model ${properties.model}, written ${letters.count { it != null }}/${letters.size}")
        // 규칙에 맞지 않는 답은 실패로 돌아와 다시 시도된다. 대부분은 한 번에 써져야 한다
        assertThat(letters.count { it != null }).isGreaterThanOrEqualTo(INPUTS.size - 1)
    }

    private companion object {
        const val PACE_MILLIS = 1_500L

        /** [received]는 처치된 몬스터, 받은 공감, 받은 댓글의 수다. */
        fun input(
            posts: Int,
            emotions: Map<ClassifiedEmotion, Int>,
            received: Triple<Int, Int, Int>,
            previous: Pair<Int, ClassifiedEmotion>? = null,
        ) = WeeklyLetterInput(
            postCount = posts,
            emotionCounts = ClassifiedEmotion.entries.associateWith { emotions[it] ?: 0 },
            unanalyzedCount = posts - emotions.values.sum(),
            topEmotion = emotions.maxByOrNull { it.value }?.key,
            defeatedCount = received.first,
            receivedLikes = received.second,
            receivedComments = received.third,
            previousPostCount = previous?.first,
            previousTopEmotion = previous?.second,
        )

        // 검증용으로 지어낸 수치다
        val INPUTS =
            listOf(
                input(3, mapOf(ClassifiedEmotion.ANXIETY to 2, ClassifiedEmotion.IRRITATION to 1), Triple(1, 12, 4)),
                input(1, mapOf(ClassifiedEmotion.LONELINESS to 1), Triple(0, 0, 0)),
                input(
                    6,
                    mapOf(ClassifiedEmotion.LETHARGY to 4, ClassifiedEmotion.SELF_DEPRECATION to 2),
                    Triple(3, 25, 11),
                    2 to ClassifiedEmotion.ANXIETY,
                ),
                input(2, emptyMap(), Triple(0, 3, 1)),
                input(4, mapOf(ClassifiedEmotion.IRRITATION to 4), Triple(2, 8, 6), 5 to ClassifiedEmotion.IRRITATION),
            )
    }
}
