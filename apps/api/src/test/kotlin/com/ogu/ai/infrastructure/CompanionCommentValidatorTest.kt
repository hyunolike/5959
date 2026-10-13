package com.ogu.ai.infrastructure

import com.ogu.ai.CompanionCommentFailed
import com.ogu.ai.EmotionAnalysisFailed
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** AI가 쓴 댓글을 올리기 전의 모양과 규칙 검사(009 research R6). 문장은 모두 검증용으로 지어낸 것이다. */
class CompanionCommentValidatorTest {
    @Test
    fun `평범한 위로는 앞뒤 공백을 떼고 그대로 쓴다`() {
        val comment = CompanionCommentValidator.validated("  오늘 하루 정말 애썼어요. 곁에서 응원할게요.\n")

        assertThat(comment).isEqualTo("오늘 하루 정말 애썼어요. 곁에서 응원할게요.")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "그 팀장 너무하네요. 듣는 제가 다 화가 나요.",
            "퇴근하고 맛있는 거 드세요. 3일만 버티면 주말이에요!",
            "약속 하나 할게요. 여기서는 언제든 털어놓아도 돼요.",
            "ㅋㅋㅋ 회의가 길면 다리가 먼저 퇴사하고 싶어 하죠.",
            "한 번에 다 하려고 하지 말고 오늘은 한 가지만 해 봐요.",
        ],
    )
    fun `US4-AC1 말투에 맞는 평범한 댓글은 통과한다`(comment: String) {
        assertThat(CompanionCommentValidator.validated(comment)).isEqualTo(comment)
    }

    @Test
    fun `US3-AC5 빈 답과 300자를 넘는 답은 실패다`() {
        listOf(null, "", "   \n", "가".repeat(301)).forEach(::assertRejected)

        assertThat(CompanionCommentValidator.validated("가".repeat(300))).hasSize(300)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "그건 우울증 초기 증상 같아요.",
            "번아웃 증후군으로 진단받을 수 있어요.",
            "병원에 한번 가 보세요.",
            "정신과 상담을 받아 보는 건 어때요?",
            "수면제를 처방받으면 나아질 거예요.",
            "약을 먹으면 금방 괜찮아져요.",
            "전문 치료가 필요해 보여요.",
        ],
    )
    fun `US3-AC5 진단과 처방, 약과 치료의 권유는 실패다`(comment: String) {
        assertRejected(comment)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "저도 예전에 똑같은 일을 겪었어요.",
            "나도 그랬어요. 신입 때 매일 울었죠.",
            "제 경험으로는 석 달이면 지나가요.",
            "저희 회사 팀장도 딱 그래요.",
            "제가 다니던 회사에서도 그랬거든요.",
        ],
    )
    fun `US3-AC5 사람인 척한 경험은 실패다`(comment: String) {
        assertRejected(comment)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "자세한 건 https://example.com 에서 보세요.",
            "www.example.co.kr 에 좋은 글이 있어요.",
            "힘들면 010-1234-5678로 연락 주세요.",
            "help@example.com 으로 메일 주세요.",
            "오픈채팅으로 얘기해요.",
            "어느 회사 다니세요?",
            "혹시 몇 살이세요?",
        ],
    )
    fun `US3-AC5 주소와 전화번호와 메일, 개인 정보를 묻는 말은 실패다`(comment: String) {
        assertRejected(comment)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "힘들면 01012345678로 연락 주세요.",
            "궁금한 건 1588-0000에 물어보면 돼요.",
            "여기 bit.ly/abc123 한번 봐요.",
            "자세한 건 t.me/someone 에 있어요.",
        ],
    )
    fun `US3-AC5 이어 쓴 번호와 대표 번호, 짧은 주소도 실패다`(comment: String) {
        assertRejected(comment)
    }

    @Test
    fun `US4-AC4 한글이 절반이 안 되면 실패다`() {
        assertRejected("That sounds really tough. Hang in there, okay?")
        assertRejected("힘내 you can do it, everything will be fine soon")

        assertThat(CompanionCommentValidator.validated("오늘은 OKR 회의 때문에 많이 지쳤겠어요.")).contains("OKR")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\"오늘 하루 정말 애썼어요.\"",
            "“오늘 하루 정말 애썼어요.”",
            "댓글: 오늘 하루 정말 애썼어요.",
            "답변: 오늘 하루 정말 애썼어요.",
            "다정한 오구: 오늘 하루 정말 애썼어요.",
        ],
    )
    fun `US3-AC5 따옴표로 감싼 답과 머리말이 붙은 답은 실패다`(comment: String) {
        assertRejected(comment)
    }

    private fun assertRejected(raw: String?) {
        assertThatThrownBy { CompanionCommentValidator.validated(raw) }
            .describedAs("답: ${raw?.take(12)}")
            .isInstanceOfSatisfying(CompanionCommentFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.INVALID_RESPONSE)
            }
    }
}
