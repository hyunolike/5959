package com.ogu.ai.infrastructure

import com.ogu.ai.CompanionCommentFailed
import com.ogu.ai.EmotionAnalysisFailed.Kind.INVALID_RESPONSE
import com.ogu.shared.text.Grapheme

/**
 * 모델이 쓴 댓글을 올리기 전에 모양과 규칙을 확인한다(009 research R6, FR-009). 어기면 [CompanionCommentFailed]다.
 * 그 답은 올리지 않고 다시 청한다.
 *
 * - 비어 있지 않고 [MAX_LENGTH]자(그래핌) 이하다. 사람의 댓글과 같은 길이다.
 * - 한국어다. 글자 가운데 한글이 절반을 넘는다.
 * - 따옴표로 감싸거나 "댓글:" 같은 머리말을 붙이지 않았다. 모델이 답을 포장한 것이다.
 * - 주소, 전화번호, 메일이 없다.
 * - 진단과 처방, 약과 치료의 권유가 없다. 사람인 척 겪은 일을 지어내지 않았다. 개인 정보를 묻지 않는다.
 *
 * 규칙 검사는 낱말 목록이라 다 잡지 못한다. 프롬프트가 먼저 막고, 실제 모델의 댓글을 사람이 읽어 확인한다(quickstart).
 * 잘못 걸러도 댓글 하나를 다시 청할 뿐이라 넓게 잡는다.
 */
object CompanionCommentValidator {
    /** 사람의 댓글과 같은 상한(post의 `Comment.CONTENT_MAX_LENGTH`). ai 모듈은 post를 모르므로 여기에 적는다. */
    const val MAX_LENGTH = 300

    /** 사람인 척 지어낸 경험. AI 친구는 겪은 일이 없다. */
    private val PERSONAL =
        Regex(
            "(저도|나도|저\\s*역시|저\\s*또한)\\s*(예전|전에|한때|비슷|똑같|같은|그런|그랬|겪|당했)|" +
                "(제|내)\\s*경험|제가\\s*(겪|당했|다니던|일할|신입)|(저희|우리)\\s*(회사|팀)|제\\s*(상사|팀장|회사)",
        )

    /** 글쓴이의 개인 정보를 묻는 말. */
    private val PROBING = Regex("어느\\s*회사|회사\\s*이름|어디\\s*(사세|살아|다니)|몇\\s*살|나이가\\s*어떻게|이름이\\s*뭐|본명")

    fun validated(raw: String?): String {
        val comment = raw?.trim().orEmpty()
        val broken =
            comment.isEmpty() ||
                Grapheme.count(comment, MAX_LENGTH + 1) > MAX_LENGTH ||
                !GeneratedTextRules.mostlyHangul(comment) ||
                GeneratedTextRules.WRAPPED.matches(comment) ||
                GeneratedTextRules.HEADER.containsMatchIn(comment) ||
                GeneratedTextRules.hasContact(comment) ||
                GeneratedTextRules.MEDICAL.containsMatchIn(comment) ||
                PERSONAL.containsMatchIn(comment) ||
                PROBING.containsMatchIn(comment)
        if (broken) throw CompanionCommentFailed(INVALID_RESPONSE)
        return comment
    }
}
