package com.ogu.ai.infrastructure

import com.ogu.ai.CompanionPostFailed
import com.ogu.ai.CompanionPostInput
import com.ogu.ai.CompanionTone
import com.ogu.ai.CompanionVoice
import com.ogu.shared.config.AiProperties
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Duration

/**
 * 실제 공급자와 기본 모델이 쓴 AI 친구의 글 30개를 출력한다(012 SC-013, research R15). 소재 열 가지에 친구 셋이다.
 * 직장인의 고민 글로 자연스럽게 읽히는지, 실제 회사나 사람이 드러나지 않는지는 수치로 재지 않는다. 사람이 읽고 프롬프트와
 * 검사를 다듬는다. 모델이나 프롬프트를 바꿨을 때, 그리고 글쓰기를 켜기 전에 돌린다.
 *
 * ```
 * OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*CompanionPostLiveTest*" -i | grep LIVE
 * ```
 */
@EnabledIfEnvironmentVariable(named = "OGU_RUN_AI_EVAL", matches = "true")
class CompanionPostLiveTest {
    @Test
    fun `SC-013 기본 모델이 소재 열 가지에 친구 셋으로 규칙에 맞는 글을 쓴다`() {
        val properties = AiProperties(apiKey = System.getenv("AI_API_KEY").orEmpty())
        val limiter = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(properties.timeout).build())
        val breaker = CircuitBreaker.ofDefaults("companionPostLive")
        val writer = SpringAiCompanionPostWriter.create(properties, breaker, limiter)

        val posts =
            TOPICS.flatMapIndexed { index, topic ->
                CompanionVoice.entries.map { voice ->
                    val tone = CompanionTone.entries[(index + voice.ordinal) % CompanionTone.entries.size]
                    val key = "LIVE:$index:${voice.name}"
                    val started = System.nanoTime()
                    val post =
                        try {
                            writer.write(key, CompanionPostInput(topic, tone, voice))
                        } catch (e: CompanionPostFailed) {
                            println("LIVE post $key failed: ${e.kind}")
                            null
                        }
                    val millis = Duration.ofNanos(System.nanoTime() - started).toMillis()
                    println("LIVE post [${voice.label}][${tone.label}] #$index (${millis}ms): $post")
                    Thread.sleep(PACE_MILLIS)
                    post
                }
            }

        val written = posts.count { it != null }
        println("LIVE post: model ${properties.model}, written $written/${posts.size}")
        // 규칙에 맞지 않는 답은 실패로 돌아와 다시 청한다. 열에 아홉은 한 번에 써져야 한다(SC-013)
        assertThat(written).isGreaterThanOrEqualTo(posts.size * 9 / 10)
    }

    private companion object {
        const val PACE_MILLIS = 1_500L

        // 운영의 소재 목록(companion의 PostTopics)에서 고른 열 가지다. ai 모듈은 companion을 모르므로 옮겨 적었다
        val TOPICS =
            listOf(
                "회의에서 준비한 말을 끝내 꺼내지 못했다.",
                "퇴근 십 분 전에 내일 아침까지 해 달라는 일을 받았다.",
                "내가 정리한 자료가 다른 사람의 이름으로 보고됐다.",
                "보고서를 다시 해 오라는데 무엇이 문제인지는 듣지 못했다.",
                "점심을 같이 먹을 사람이 없어 자리에서 혼자 먹었다.",
                "일요일 밤마다 내일 출근할 생각에 잠이 오지 않는다.",
                "같이 들어온 동기가 먼저 인정받는 것을 보고 마음이 복잡했다.",
                "휴가를 쓰겠다고 말하는 것이 눈치가 보인다.",
                "이직을 생각하지만 지금 떠나는 것이 맞는지 모르겠다.",
                "메일을 잘못된 사람에게 보내고 하루 종일 마음이 쓰였다.",
            )
    }
}
