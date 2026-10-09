package com.ogu.safety.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** T045: 욕설 가리기(005 US5, research R6). 문장은 모두 검증용으로 지어낸 것이다. */
class ProfanityMaskTest {
    private val terms =
        SafetyTerms(
            crisis = emptySet(),
            concern = emptySet(),
            profanity = setOf("시발", "병신", "개새끼", "새끼"),
            allow = setOf("시발점", "시발역"),
        )

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        이런 시발 진짜|이런 ** 진짜
        병신같은 회의|**같은 회의
        시발시발|****
        아 시발 저 병신이|아 ** 저 **이""",
    )
    fun `US5-AC1 욕설을 글자 수만큼 별표로 바꾸고 여러 번 나오면 모두 가린다`(
        raw: String,
        expected: String,
    ) {
        assertThat(ProfanityMasker.mask(raw, terms)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        진짜 시 발 이네|진짜 *** 이네
        시.발 뭐야|*** 뭐야
        시~~발!|****!
        ＳＩ 병-신|ＳＩ ***""",
    )
    fun `US5-AC2 사이에 낀 공백과 기호까지 가린다`(
        raw: String,
        expected: String,
    ) {
        assertThat(ProfanityMasker.mask(raw, terms)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        모든 일의 시발점이다|모든 일의 시발점이다
        시발역에서 탔어요|시발역에서 탔어요
        시발점인데 시발 왜|시발점인데 ** 왜
        시발 점심 뭐 먹지|** 점심 뭐 먹지""",
    )
    fun `US5-AC3 허용 목록의 낱말 안에 든 욕설은 가리지 않고, 띄어 써서 생긴 겹침은 가린다`(
        raw: String,
        expected: String,
    ) {
        assertThat(ProfanityMasker.mask(raw, terms)).isEqualTo(expected)
    }

    @Test
    fun `겹치는 낱말은 긴 쪽까지 모두 가린다`() {
        assertThat(ProfanityMasker.mask("저 개새끼가", terms)).isEqualTo("저 ***가")
        assertThat(ProfanityMasker.mask("저 새끼가", terms)).isEqualTo("저 **가")
    }

    @Test
    fun `결합 이모지 옆에서도 위치가 어긋나지 않고 사이에 낀 이모지는 한 글자로 가린다`() {
        assertThat(ProfanityMasker.mask("👨‍👩‍👧 시발 🇰🇷", terms)).isEqualTo("👨‍👩‍👧 ** 🇰🇷")
        assertThat(ProfanityMasker.mask("시👨‍👩‍👧발 끝", terms)).isEqualTo("*** 끝")
    }

    @Test
    fun `욕설이 없거나 목록이 비었으면 같은 문자열 객체를 돌려준다`() {
        val clean = "오늘 회의가 너무 길었어요"
        val dirty = "이런 시발"

        assertThat(ProfanityMasker.mask(clean, terms)).isSameAs(clean)
        assertThat(ProfanityMasker.mask("모든 일의 시발점", terms)).isEqualTo("모든 일의 시발점")
        assertThat(ProfanityMasker.mask(dirty, terms.copy(profanity = emptySet()))).isSameAs(dirty)
        assertThat(ProfanityMasker.mask("", terms)).isEmpty()
    }
}
