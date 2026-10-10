package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.WeeklyLetterFailed
import com.ogu.ai.WeeklyLetterInput
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 모델이 쓴 편지를 보이기 전의 확인(008 research R7). */
class WeeklyLetterValidatorTest {
    private val input =
        WeeklyLetterInput(
            postCount = 3,
            emotionCounts = mapOf(ClassifiedEmotion.ANXIETY to 2, ClassifiedEmotion.IRRITATION to 1),
            unanalyzedCount = 0,
            topEmotion = ClassifiedEmotion.ANXIETY,
            defeatedCount = 1,
            receivedLikes = 12,
            receivedComments = 4,
        )

    @Test
    fun `규칙에 맞는 편지는 앞뒤 공백을 떼고 그대로 쓴다`() {
        val letter = WeeklyLetterValidator.validated("  글 3개에 공감 12개와 댓글 4개가 닿았어요.\n", input, 300)

        assertThat(letter).isEqualTo("글 3개에 공감 12개와 댓글 4개가 닿았어요.")
    }

    @Test
    fun `US2-AC7 비어 있거나 길이를 넘는 답은 실패다`() {
        listOf(null, "", "   \n", "가".repeat(301)).forEach { raw ->
            assertThatThrownBy { WeeklyLetterValidator.validated(raw, input, 300) }
                .describedAs("답: ${raw?.take(5)}")
                .isInstanceOfSatisfying(WeeklyLetterFailed::class.java) {
                    assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.INVALID_RESPONSE)
                }
        }
        assertThat(WeeklyLetterValidator.validated("가".repeat(300), input, 300)).hasSize(300)
    }

    @Test
    fun `US2-AC7 보낸 수치에 없는 숫자가 든 답은 실패다`() {
        listOf("공감을 20개 받으셨어요.", "글의 80%가 불안이었어요.", "지난 10월 5일부터의 한 주였어요.").forEach { raw ->
            assertThatThrownBy { WeeklyLetterValidator.validated(raw, input, 300) }
                .describedAs(raw)
                .isInstanceOf(WeeklyLetterFailed::class.java)
        }
    }

    @Test
    fun `US2-AC7 이모지가 든 답은 실패다`() {
        assertThatThrownBy { WeeklyLetterValidator.validated("곁에 있었어요. 🙏", input, 300) }
            .isInstanceOf(WeeklyLetterFailed::class.java)
    }

    @Test
    fun `한 주를 가리키는 1과 7은 수치에 없어도 된다`() {
        assertThat(WeeklyLetterValidator.validated("지난 1주일, 7일 동안 애쓰셨어요.", input, 300)).isNotBlank()
    }

    @Test
    fun `보낼 수 있는 숫자는 수치뿐이다`() {
        assertThat(input.numbers()).containsExactlyInAnyOrder(3, 2, 1, 0, 12, 4)
    }
}
