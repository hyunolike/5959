package com.ogu.safety.application

import com.ogu.safety.RiskLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 문장은 모두 검증용으로 지어낸 것이다. */
class KeywordRuleTest {
    private val terms =
        SafetyTerms(
            crisis = setOf("죽고싶", "자해"),
            concern = setOf("다포기하고싶", "버틸수없"),
        )

    @ParameterizedTest
    @ValueSource(
        strings = [
            "요즘 너무 힘들어서 죽고 싶어요",
            "죽고싶다",
            "자해를 생각했어요",
            // 부정문과 인용도 위기로 본다. 놓치는 것보다 잘못 숨기는 쪽을 택했다
            "죽고 싶지는 않아요",
            "친구가 '죽고 싶다'고 말했어요",
        ],
    )
    fun `위기 목록의 표현이 들어 있으면 CRISIS다`(content: String) {
        assertThat(KeywordRule.level(content, terms)).isEqualTo(RiskLevel.CRISIS)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "죽 고 싶 어 요",
            "죽.고.싶.어",
            "죽~고~싶~다",
            "죽고싶어어어어어",
            "죽고\n싶다",
            "죽ㆍ고ㆍ싶ㆍ다",
        ],
    )
    fun `US2-AC4 위기 표현 사이에 공백이나 기호를 끼워도 같은 표현으로 알아본다`(content: String) {
        assertThat(KeywordRule.level(content, terms)).isEqualTo(RiskLevel.CRISIS)
    }

    @Test
    fun `우려 목록에만 걸리면 CONCERN이고 위기 목록이 함께 걸리면 CRISIS다`() {
        assertThat(KeywordRule.level("이제 다 포기하고 싶어요", terms)).isEqualTo(RiskLevel.CONCERN)
        assertThat(KeywordRule.level("더는 버틸 수 없어요", terms)).isEqualTo(RiskLevel.CONCERN)
        assertThat(KeywordRule.level("버틸 수 없어서 죽고 싶어요", terms)).isEqualTo(RiskLevel.CRISIS)
    }

    @ParameterizedTest
    @ValueSource(strings = ["오늘 회의가 너무 길었어요", "", "   ", "😀😀😀", "죽을 만큼 맛있는 떡볶이", "포기하지 않을 거예요"])
    fun `목록에 없으면 NONE이다`(content: String) {
        assertThat(KeywordRule.level(content, terms)).isEqualTo(RiskLevel.NONE)
    }

    @Test
    fun `내장 목록은 원본의 위기 표현 여섯 개를 잡는다`() {
        listOf("죽고 싶다", "자살", "자해", "스스로 목숨을", "삶을 끝내고", "죽어버리고 싶다").forEach {
            assertThat(KeywordRule.level(it, SafetyTerms.BUILT_IN)).describedAs(it).isEqualTo(RiskLevel.CRISIS)
        }
    }

    @Test
    fun `단계는 NONE, CONCERN, CRISIS 순으로 높고 max는 더 높은 쪽을 고른다`() {
        assertThat(RiskLevel.NONE.max(RiskLevel.CONCERN)).isEqualTo(RiskLevel.CONCERN)
        assertThat(RiskLevel.CRISIS.max(RiskLevel.CONCERN)).isEqualTo(RiskLevel.CRISIS)
        assertThat(RiskLevel.of("CRISIS")).isEqualTo(RiskLevel.CRISIS)
        assertThat(RiskLevel.of("UNKNOWN")).isEqualTo(RiskLevel.NONE)
    }
}
