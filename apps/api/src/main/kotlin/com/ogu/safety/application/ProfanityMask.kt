package com.ogu.safety.application

import com.ogu.shared.text.ContentMask
import com.ogu.shared.text.Grapheme
import org.springframework.stereotype.Component

/**
 * 욕설 가리기(005 US5, research R6). 원문은 그대로 두고 응답을 만들 때 가린다. 낱말은 [TermCache]가 올려 둔 목록이라
 * 운영자가 목록을 고치면 예전 글에도 다음 조회부터 적용된다. 보는 사람이 작성자면 부르는 쪽이 건너뛴다.
 */
@Component
class ProfanityMask(
    private val termCache: TermCache,
) : ContentMask {
    override fun mask(text: String): String = ProfanityMasker.mask(text, termCache.terms())
}

/**
 * 욕설을 글자 수만큼 `*`로 바꾼다. 견주기는 [TextNormalizer]로 다듬은 글에서 하고, 가리기는 그 글자들이 온 원문의 구간에
 * 한다. 그래서 사이에 끼워 넣은 공백과 기호까지 함께 가려진다(US5-AC2).
 *
 * 허용 목록의 낱말 안에 든 욕설은 가리지 않는다(US5-AC3, "시발점"). 다만 허용 낱말이 원문에서 끊기지 않고 이어져 있을
 * 때만이다. "시발 점심"은 다듬으면 "시발점"이 들어 있지만 원문에서는 띄어 쓴 두 말이므로 가린다.
 */
object ProfanityMasker {
    private const val STAR = '*'

    fun mask(
        text: String,
        terms: SafetyTerms,
    ): String {
        if (terms.profanity.isEmpty() || text.isEmpty()) return text
        val ranges = rangesToMask(TextNormalizer.normalize(text), terms)
        return if (ranges.isEmpty()) text else replace(text, merge(ranges))
    }

    /** 가릴 원문 구간. 허용 낱말 안에 든 욕설은 뺀다. */
    private fun rangesToMask(
        normalized: NormalizedText,
        terms: SafetyTerms,
    ): List<IntRange> {
        val matches = occurrences(normalized.text, terms.profanity)
        if (matches.isEmpty()) return emptyList()

        val allowed =
            occurrences(normalized.text, terms.allow)
                .filter { normalized.originalRange(it.first, it.last + 1).isUnbroken(it) }
        return matches
            .filterNot { match -> allowed.any { match.first >= it.first && match.last <= it.last } }
            .map { normalized.originalRange(it.first, it.last + 1) }
    }

    /** [text] 안에서 [terms]의 낱말이 나오는 모든 자리. */
    private fun occurrences(
        text: String,
        terms: Set<String>,
    ): List<IntRange> =
        terms.filter { it.isNotEmpty() }.flatMap { term ->
            generateSequence(text.indexOf(term)) { from -> text.indexOf(term, from + 1) }
                .takeWhile { it >= 0 }
                .map { it until it + term.length }
                .toList()
        }

    /** 원문의 구간이 다듬은 구간과 길이가 같으면 사이에 버려진 글자가 없다. */
    private fun IntRange.isUnbroken(normalizedRange: IntRange): Boolean = count() == normalizedRange.count()

    private fun merge(ranges: List<IntRange>): List<IntRange> =
        ranges.sortedBy { it.first }.fold(mutableListOf()) { merged, range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
            merged
        }

    private fun replace(
        text: String,
        ranges: List<IntRange>,
    ): String {
        val result = StringBuilder(text.length)
        var cursor = 0
        ranges.forEach { range ->
            result.append(text, cursor, range.first)
            repeat(Grapheme.count(text.substring(range.first, range.last + 1))) { result.append(STAR) }
            cursor = range.last + 1
        }
        return result.append(text, cursor, text.length).toString()
    }
}
