package com.ogu.ai.infrastructure

import com.ogu.ai.EmotionAnalysisFailed.Kind.INVALID_RESPONSE
import com.ogu.ai.WeeklyLetterFailed
import com.ogu.ai.WeeklyLetterInput
import com.ogu.shared.text.Grapheme

/**
 * 모델이 쓴 편지를 회원에게 보이기 전에 확인한다(008 research R7, US2-AC7). 어기면 [WeeklyLetterFailed]다. 그 답은
 * 편지로 쓰지 않고 다시 시도한다.
 *
 * - 비어 있지 않다.
 * - [maxLength]자(그래핌) 이하다.
 * - 보낸 수치에 없는 숫자가 없다. 모델이 수치를 지어내는 것을 막는 가장 싼 방법이다. 한 주를 가리키는 1과 7은 둔다.
 * - 이모지가 없다. 프롬프트로 막아도 가끔 붙여 온다.
 */
object WeeklyLetterValidator {
    private val NUMBER = Regex("\\d+")
    private val ALWAYS_ALLOWED = setOf(1, 7)
    private val EMOJI = Regex("\\p{IsExtended_Pictographic}")

    fun validated(
        raw: String?,
        input: WeeklyLetterInput,
        maxLength: Int,
    ): String {
        val letter = raw?.trim().orEmpty()
        val allowed = input.numbers() + ALWAYS_ALLOWED
        val invented = NUMBER.findAll(letter).any { it.value.toIntOrNull() !in allowed }
        val tooLong = Grapheme.count(letter) > maxLength
        val broken = letter.isEmpty() || tooLong || invented || EMOJI.containsMatchIn(letter)
        if (broken) throw WeeklyLetterFailed(INVALID_RESPONSE)
        return letter
    }
}
