package com.ogu.shared.text

import java.text.BreakIterator
import java.util.Locale

/**
 * 사람이 보는 글자(grapheme) 수를 센다. 결합 이모지(👨‍👩‍👧)와 국기(🇰🇷)도 한 글자다.
 * 웹은 `Intl.Segmenter`로 같은 값을 낸다(apps/web/src/shared/lib/grapheme.ts). 서버 기본 로캘에 따라 값이 달라지지
 * 않도록 [Locale.ROOT]로 고정한다.
 */
object Grapheme {
    fun count(text: String): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    /** 앞에서부터 [count]글자까지 자른다. 결합 이모지를 중간에서 끊지 않는다. */
    fun take(
        text: String,
        count: Int,
    ): String {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var end = 0
        var taken = 0
        while (taken < count) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) break
            end = next
            taken++
        }
        return text.substring(0, end)
    }
}
