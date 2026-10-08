package com.ogu.ai

import com.ogu.ai.infrastructure.FakeRiskClassifier
import com.ogu.ai.infrastructure.RiskResponseParser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** T026: 위험 분류 응답 해석과 가짜 분류기(005 research R3). */
class RiskClassifierTests {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"level":"NONE"}|NONE""",
            """{"level":"CONCERN"}|CONCERN""",
            """{"level":"CRISIS"}|CRISIS""",
            """  {"level" : "CRISIS"}  |CRISIS""",
            """{"level":"CONCERN","reason":"무시하는 필드"}|CONCERN""",
        ],
    )
    fun `응답의 level을 읽는다`(
        raw: String,
        expected: ClassifiedRisk,
    ) {
        assertThat(RiskResponseParser.parse(raw)).isEqualTo(expected)
    }

    @Test
    fun `코드 블록으로 감싼 응답은 벗겨서 읽는다`() {
        assertThat(RiskResponseParser.parse("```json\n{\"level\":\"CRISIS\"}\n```")).isEqualTo(ClassifiedRisk.CRISIS)
        assertThat(RiskResponseParser.parse("```\n{\"level\":\"NONE\"}\n```")).isEqualTo(ClassifiedRisk.NONE)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "위기입니다",
            """{"level":"HIGH"}""",
            """{"level":"crisis"}""",
            """{"level":null}""",
            """{"level":2}""",
            """{"risk":"CRISIS"}""",
            """["CRISIS"]""",
            """{"level":"CRISIS"""",
        ],
    )
    fun `목록 밖의 값, 빠진 필드, 깨진 JSON은 INVALID_RESPONSE로 실패한다`(raw: String) {
        assertThatThrownBy { RiskResponseParser.parse(raw) }
            .isInstanceOfSatisfying(RiskClassificationFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.INVALID_RESPONSE)
                // 예외 메시지에 응답 원문을 싣지 않는다
                assertThat(it.message).doesNotContain("level").doesNotContain("위기입니다")
            }
    }

    @Test
    fun `응답이 없으면 실패한다`() {
        assertThatThrownBy { RiskResponseParser.parse(null) }.isInstanceOf(RiskClassificationFailed::class.java)
    }

    @Test
    fun `가짜 분류기는 본문의 표지로 단계를 정한다`() {
        val fake = FakeRiskClassifier()

        assertThat(fake.classify("POST:1", "평범한 글")).isEqualTo(ClassifiedRisk.NONE)
        assertThat(fake.classify("POST:2", "[우려] 지친 글")).isEqualTo(ClassifiedRisk.CONCERN)
        assertThat(fake.classify("POST:3", "[위기] 목록에 없는 표현")).isEqualTo(ClassifiedRisk.CRISIS)
        // 감정 분석기의 머리말 뒤에 붙여 쓸 수 있다
        assertThat(fake.classify("POST:4", "[불안:낮음] [위기] 함께 쓴 글")).isEqualTo(ClassifiedRisk.CRISIS)
    }

    @Test
    fun `가짜 분류기는 실패 표지에 따라 항상 또는 대상마다 처음 N번만 실패한다`() {
        val fake = FakeRiskClassifier()

        repeat(3) {
            assertThatThrownBy { fake.classify("POST:1", "[위험분류실패] [위기] 글") }
                .isInstanceOf(RiskClassificationFailed::class.java)
        }
        repeat(2) {
            assertThatThrownBy { fake.classify("POST:2", "[위험분류실패:2] [우려] 글") }
                .isInstanceOf(RiskClassificationFailed::class.java)
        }
        assertThat(fake.classify("POST:2", "[위험분류실패:2] [우려] 글")).isEqualTo(ClassifiedRisk.CONCERN)
        // 횟수는 대상마다 따로 센다
        assertThatThrownBy { fake.classify("POST:3", "[위험분류실패:2] 글") }
            .isInstanceOf(RiskClassificationFailed::class.java)
    }

    @Test
    fun `키가 없을 때 쓰는 분류기는 본문을 보내지 않고 바로 실패한다`() {
        assertThatThrownBy { FakeRiskClassifier.Disabled().classify("POST:1", "글") }
            .isInstanceOfSatisfying(RiskClassificationFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR)
            }
    }
}
