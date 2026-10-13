package com.ogu.ai.infrastructure

import com.ogu.ai.CompanionPostFailed
import com.ogu.ai.EmotionAnalysisFailed
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** AI 친구의 글이 올라가기 전에 보는 모양과 규칙(012 research R15, FR-025). */
class CompanionPostValidatorTest {
    @Test
    fun `US3-AC7 평범한 고민 글은 앞뒤 공백을 떼고 통과한다`() {
        val post = CompanionPostValidator.validated("  $ORDINARY\n")

        assertThat(post).isEqualTo(ORDINARY)
    }

    @Test
    fun `US3-AC7 지어 쓴 일인칭 이야기는 막지 않는다`() {
        // 댓글에서는 사람인 척한 경험으로 막는 말들이다. 글은 지어 쓴 이야기이고 화면이 그것을 밝힌다
        val firstPerson = "우리 팀 회의가 세 시간째 이어졌어요. 제 팀장님은 끝낼 생각이 없어 보였어요. 저도 예전에 겪은 일이라 더 지쳤어요."

        assertThat(CompanionPostValidator.validated(firstPerson)).isEqualTo(firstPerson)
    }

    @Test
    fun `US5-AC2 빈 글과 500자를 넘는 글은 실패다`() {
        assertRejected(null, "", "   \n ")
        assertRejected("가".repeat(501))
        assertThat(CompanionPostValidator.validated("가".repeat(500))).hasSize(500)
    }

    @Test
    fun `US5-AC2 한글이 절반이 안 되는 글은 실패다`() {
        assertRejected("Today my meeting ran for three hours and nobody said a word. 지쳤어요.")
    }

    @Test
    fun `US5-AC2 따옴표로 감싼 글과 머리말을 붙인 글은 실패다`() {
        assertRejected("\"$ORDINARY\"", "“$ORDINARY”", "제목: 회의가 길었어요. $ORDINARY", "글: $ORDINARY")
    }

    @Test
    fun `US5-AC2 연락처와 외부 주소가 든 글은 실패다`() {
        assertRejected(
            "$ORDINARY 같은 고민이면 010-1234-5678로 연락 주세요.",
            "$ORDINARY 자세한 얘기는 https://example.com 에 적었어요.",
            "$ORDINARY 오픈채팅으로 얘기해요.",
        )
    }

    @Test
    fun `US5-AC2 진단과 처방, 약과 치료의 권유가 든 글은 실패다`() {
        assertRejected(
            "$ORDINARY 아무래도 우울증인 것 같아요.",
            "$ORDINARY 정신과에 가 봐야 할까요.",
            "$ORDINARY 수면제 없이는 잠이 안 와요.",
        )
    }

    @Test
    fun `US5-AC2 죽음이나 자해를 떠올리게 하는 글은 실패다`() {
        assertRejected(
            "$ORDINARY 그냥 사라지고 싶어요.",
            "$ORDINARY 일이 많아 죽겠어요.",
            "$ORDINARY 죽고 싶다는 생각이 들었어요.",
            "$ORDINARY 살기 싫어요.",
        )
    }

    @Test
    fun `US5-AC2 사람이라고 내세우는 글은 실패다`() {
        assertRejected(
            "$ORDINARY 저는 사람이에요.",
            "$ORDINARY 저는 AI가 아니에요.",
            "$ORDINARY 실제로 겪은 일이에요.",
            "$ORDINARY 지어낸 게 아니라 실화예요.",
        )
    }

    @Test
    fun `US5-AC2 회원이나 다른 글을 가리키는 글은 실패다`() {
        assertRejected(
            "$ORDINARY 회원님들은 어떠세요.",
            "$ORDINARY 다른 글을 보니 저만 그런 게 아니네요.",
            "$ORDINARY 어떤 분이 쓴 글을 읽고 용기를 냈어요.",
        )
    }

    @Test
    fun `US5-AC2 널리 알려진 회사의 이름이 든 글은 실패다`() {
        assertRejected(
            "$ORDINARY 삼성에 다니는 친구는 안 그렇대요.",
            "$ORDINARY 네이버로 이직하고 싶어요.",
            "$ORDINARY 주식회사 오구에서 일해요.",
        )
        // 이름이 아닌 낱말의 일부는 걸리지 않는다
        val deadline = "마감 데드라인이 하루 당겨졌어요. 가이드라인도 없이 다시 하라는 말만 들었어요."
        assertThat(CompanionPostValidator.validated(deadline)).isEqualTo(deadline)
    }

    private fun assertRejected(vararg raws: String?) {
        for (raw in raws) {
            assertThatThrownBy { CompanionPostValidator.validated(raw) }
                .describedAs(raw)
                .isInstanceOfSatisfying(CompanionPostFailed::class.java) {
                    assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.INVALID_RESPONSE)
                }
        }
    }

    private companion object {
        const val ORDINARY = "회의에서 준비한 말을 끝내 꺼내지 못했어요. 다들 바빠 보여서 손을 들 틈이 없었어요."
    }
}
