package com.ogu.shared.text

import java.text.BreakIterator

/**
 * 사람이 보는 글자(grapheme) 수를 센다. 결합 이모지(👨‍👩‍👧)와 국기(🇰🇷)도 한 글자다.
 * 웹은 `Intl.Segmenter`로 같은 값을 낸다(apps/web/src/shared/lib/grapheme.ts).
 */
object Grapheme {
    fun count(text: String): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }
}
