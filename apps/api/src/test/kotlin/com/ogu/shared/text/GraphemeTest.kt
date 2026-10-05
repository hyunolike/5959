package com.ogu.shared.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.Locale

/**
 * T010: 본문 글자 수는 사람이 보는 글자(grapheme) 단위로 센다. 웹의 `Intl.Segmenter`와 같은 값을 내야 한다.
 */
class GraphemeTest {
    @ParameterizedTest(name = "\"{0}\"는 {1}글자다")
    @CsvSource(
        "안녕하세요, 5",
        "hello, 5",
        "👍, 1",
        "👨‍👩‍👧, 1",
        "🇰🇷, 1",
        "오늘 👍 🇰🇷, 6",
    )
    fun `한글, 영문, 이모지, 결합 이모지, 국기를 한 글자씩 센다`(
        text: String,
        expected: Int,
    ) {
        assertThat(Grapheme.count(text)).isEqualTo(expected)
    }

    @Test
    fun `상한을 주면 그 수에 이르는 즉시 세기를 멈춘다`() {
        assertThat(Grapheme.count("가".repeat(1_000), limit = 501)).isEqualTo(501)
        assertThat(Grapheme.count("오늘 👍", limit = 10)).isEqualTo(4)
    }

    @Test
    fun `서버 기본 로캘이 달라도 같은 수를 센다`() {
        val original = Locale.getDefault()
        val text = "오늘 👍 🇰🇷 ภาษาไทย"
        val expected = Grapheme.count(text)
        try {
            listOf(Locale.forLanguageTag("th-TH"), Locale.forLanguageTag("tr-TR"), Locale.JAPAN).forEach { locale ->
                Locale.setDefault(locale)
                assertThat(Grapheme.count(text)).describedAs(locale.toLanguageTag()).isEqualTo(expected)
                assertThat(Grapheme.take(text, 4)).isEqualTo("오늘 👍")
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `빈 문자열은 0글자다`() {
        assertThat(Grapheme.count("")).isZero()
    }

    @Test
    fun `이모지 500개는 500글자다`() {
        assertThat(Grapheme.count("👨‍👩‍👧".repeat(500))).isEqualTo(500)
    }

    @Test
    fun `앞에서부터 글자 단위로 자른다`() {
        assertThat(Grapheme.take("👨‍👩‍👧가나다", 2)).isEqualTo("👨‍👩‍👧가")
        assertThat(Grapheme.take("🇰🇷🇰🇷", 1)).isEqualTo("🇰🇷")
        assertThat(Grapheme.take("가나", 5)).isEqualTo("가나")
        assertThat(Grapheme.take("", 3)).isEmpty()
        assertThat(Grapheme.take("가나", 0)).isEmpty()
    }
}
