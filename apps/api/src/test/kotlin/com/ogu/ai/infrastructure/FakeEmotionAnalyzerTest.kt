package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.ClassifiedIntensity
import com.ogu.ai.EmotionAnalysisFailed
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * e2e 프로필과 테스트에서 쓰는 가짜 분석기 규칙(research R3, quickstart.md).
 */
class FakeEmotionAnalyzerTest {
    private val analyzer = FakeEmotionAnalyzer()

    @Test
    fun `본문이 감정과 강도 머리말로 시작하면 그 값을 돌려준다`() {
        val cases =
            mapOf(
                "[불안:높음] 내일 발표" to (ClassifiedEmotion.ANXIETY to ClassifiedIntensity.HIGH),
                "[무기력:보통] 아무것도" to (ClassifiedEmotion.LETHARGY to ClassifiedIntensity.MEDIUM),
                "[외로움:낮음] 혼자" to (ClassifiedEmotion.LONELINESS to ClassifiedIntensity.LOW),
                "[자기비하:높음] 나는" to (ClassifiedEmotion.SELF_DEPRECATION to ClassifiedIntensity.HIGH),
                "[짜증:보통] 화나" to (ClassifiedEmotion.IRRITATION to ClassifiedIntensity.MEDIUM),
            )

        cases.forEach { (content, expected) ->
            val result = analyzer.analyze(1L, content)
            assertThat(result.emotion to result.intensity).describedAs(content).isEqualTo(expected)
        }
    }

    @Test
    fun `실패 머리말이면 항상 UPSTREAM_ERROR로 실패한다`() {
        repeat(3) {
            assertThatThrownBy { analyzer.analyze(2L, "[실패] 오늘은") }
                .isInstanceOfSatisfying(EmotionAnalysisFailed::class.java) {
                    assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR)
                }
        }
    }

    @Test
    fun `실패 N 머리말이면 글마다 처음 N번만 실패하고 그다음에는 길이로 정한 값을 돌려준다`() {
        val content = "[실패:2] 오늘은 아무것도 하기 싫다"
        repeat(2) {
            assertThatThrownBy { analyzer.analyze(3L, content) }.isInstanceOf(EmotionAnalysisFailed::class.java)
        }
        // 다른 글은 따로 센다
        assertThatThrownBy { analyzer.analyze(4L, content) }.isInstanceOf(EmotionAnalysisFailed::class.java)

        val result = analyzer.analyze(3L, content)

        // 길이는 머리말을 뺀 본문으로 센다
        assertThat(result).isEqualTo(analyzer.analyze(99L, "오늘은 아무것도 하기 싫다"))
        assertThat(analyzer.analyze(3L, content)).isEqualTo(result)
    }

    @Test
    fun `머리말이 없으면 본문 글자 수로 정한 같은 값을 매번 돌려준다`() {
        val short = analyzer.analyze(5L, "가".repeat(10))
        val medium = analyzer.analyze(6L, "가".repeat(150))
        val long = analyzer.analyze(7L, "가".repeat(400))

        assertThat(short.intensity).isEqualTo(ClassifiedIntensity.LOW)
        assertThat(medium.intensity).isEqualTo(ClassifiedIntensity.MEDIUM)
        assertThat(long.intensity).isEqualTo(ClassifiedIntensity.HIGH)
        assertThat(short.emotion).isEqualTo(ClassifiedEmotion.entries[10 % 5])
        assertThat(analyzer.analyze(8L, "가".repeat(10))).isEqualTo(short)
    }
}
