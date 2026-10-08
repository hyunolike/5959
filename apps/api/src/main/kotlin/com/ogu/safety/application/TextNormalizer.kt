package com.ogu.safety.application

import java.text.Normalizer

/**
 * 정규화한 글. [text]의 i번째 글자는 원문의 `[starts[i], ends[i])` 구간에서 왔다. 욕설을 가릴 때 이 대응으로 원문의
 * 구간을 찾는다(005 research R4).
 */
class NormalizedText(
    val text: String,
    private val starts: IntArray,
    private val ends: IntArray,
) {
    /** 정규화한 글의 `[from, until)` 구간이 원문에서 차지하는 구간. 사이에 낀 공백과 기호도 들어간다. */
    fun originalRange(
        from: Int,
        until: Int,
    ): IntRange = starts[from] until ends[until - 1]
}

/**
 * 낱말 목록과 견주기 전에 글을 다듬는다(005 research R4). 공백, 문장 부호, 기호를 지우고, 호환 문자를 풀고(NFKC), 소문자로
 * 바꾸고, 같은 글자가 세 번 넘게 이어지면 두 번으로 줄인다. "죽 고 싶 어", "죽.고.싶.어", "죽고싶어어어어"가 같은 꼴이
 * 된다(US2-AC4). 낱말을 목록에 넣을 때도 같은 함수로 다듬는다.
 */
object TextNormalizer {
    private const val MAX_REPEAT = 2

    /** 글자로 분류되지만 가운뎃점처럼 끼워 넣는 데 쓰는 옛 모음 아래아(ㆍ, ᆞ). 기호와 같이 버린다. */
    private val DOT_LETTERS = setOf(0x318D, 0x119E)

    fun normalize(raw: String): NormalizedText {
        val builder = Builder(raw.length)
        var index = 0
        while (index < raw.length) {
            val codePoint = raw.codePointAt(index)
            val next = index + Character.charCount(codePoint)
            // 공백, 문장 부호, 기호는 버린다
            if (isKept(codePoint)) fold(codePoint).forEach { builder.add(it, index, next) }
            index = next
        }
        return builder.build()
    }

    private fun isKept(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) && codePoint !in DOT_LETTERS

    /** 목록에 넣을 낱말의 꼴. */
    fun termOf(raw: String): String = normalize(raw).text

    /** 글자 하나를 NFKC로 풀고 소문자로 바꾼다. 전각 영문자, 원 문자 같은 호환 문자가 보통 글자가 된다. */
    private fun fold(codePoint: Int): String {
        val single = String(Character.toChars(codePoint))
        val folded = Normalizer.normalize(single, Normalizer.Form.NFKC).lowercase()
        return folded.filter { Character.isLetterOrDigit(it) }
    }

    private class Builder(
        capacity: Int,
    ) {
        private val text = StringBuilder(capacity)
        private val starts = ArrayList<Int>(capacity)
        private val ends = ArrayList<Int>(capacity)

        fun add(
            char: Char,
            start: Int,
            end: Int,
        ) {
            if (isRepeatedTooMuch(char)) {
                // 줄인 글자의 원문 구간은 앞 글자에 붙인다. 가릴 때 반복된 글자까지 함께 가려진다
                ends[ends.lastIndex] = end
                return
            }
            text.append(char)
            starts += start
            ends += end
        }

        fun build() = NormalizedText(text.toString(), starts.toIntArray(), ends.toIntArray())

        private fun isRepeatedTooMuch(char: Char): Boolean =
            text.length >= MAX_REPEAT && (1..MAX_REPEAT).all { text[text.length - it] == char }
    }
}
