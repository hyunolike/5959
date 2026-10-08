package com.ogu.safety.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TextNormalizerTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "죽 고 싶 어|죽고싶어",
            "죽.고,싶!어?|죽고싶어",
            "죽~고_싶-어|죽고싶어",
            "죽고싶어어어어|죽고싶어어",
            "Hello  WORLD|helloworld",
            "ＡＢＣ１２３|abc123",
            "아아|아아",
            "😀가😀나|가나",
        ],
    )
    fun `공백, 부호, 기호를 지우고 호환 문자를 풀어 소문자로 바꾸며 같은 글자는 두 번까지만 남긴다`(
        raw: String,
        expected: String,
    ) {
        assertThat(TextNormalizer.normalize(raw).text).isEqualTo(expected)
        assertThat(TextNormalizer.termOf(raw)).isEqualTo(expected)
    }

    @Test
    fun `줄바꿈과 탭도 지우고, 낱자만 이어 쓴 글자도 두 번까지만 남긴다`() {
        assertThat(TextNormalizer.normalize("가\n나\t다").text).isEqualTo("가나다")
        // 호환 자모는 NFKC로 풀리면서 다른 코드가 되지만 낱말도 같은 함수로 다듬으므로 서로 맞는다
        assertThat(TextNormalizer.normalize("ㅋㅋㅋㅋㅋ").text).hasSize(2).isEqualTo(TextNormalizer.termOf("ㅋㅋ"))
        assertThat(TextNormalizer.normalize("죽ㆍ고ㆍ싶ㆍ다").text).isEqualTo("죽고싶다")
    }

    @Test
    fun `빈 글과 기호뿐인 글은 빈 문자열이 된다`() {
        assertThat(TextNormalizer.normalize("").text).isEmpty()
        assertThat(TextNormalizer.normalize(" .,!? 😀 ").text).isEmpty()
    }

    @Test
    fun `정규화한 구간이 원문의 어디였는지 돌려준다`() {
        val raw = "나는 바.보 야"
        val normalized = TextNormalizer.normalize(raw)
        val from = normalized.text.indexOf("바보")

        val range = normalized.originalRange(from, from + 2)

        // 사이에 낀 마침표까지 들어간다
        assertThat(raw.substring(range.first, range.last + 1)).isEqualTo("바.보")
    }

    @Test
    fun `줄인 반복 글자의 원문 구간은 남긴 글자에 붙는다`() {
        val raw = "와아아아아 대박"
        val normalized = TextNormalizer.normalize(raw)
        assertThat(normalized.text).isEqualTo("와아아대박")

        val range = normalized.originalRange(1, 3)

        assertThat(raw.substring(range.first, range.last + 1)).isEqualTo("아아아아")
    }

    @Test
    fun `결합 이모지 옆의 글자도 원문 위치가 어긋나지 않는다`() {
        val raw = "👨‍👩‍👧 바보 👍"
        val normalized = TextNormalizer.normalize(raw)

        val range = normalized.originalRange(0, normalized.text.length)

        assertThat(raw.substring(range.first, range.last + 1)).isEqualTo("바보")
    }
}
