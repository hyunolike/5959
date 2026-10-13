package com.ogu.ai.infrastructure

import com.ogu.ai.CompanionPostFailed
import com.ogu.ai.EmotionAnalysisFailed.Kind.INVALID_RESPONSE
import com.ogu.shared.text.Grapheme

/**
 * 모델이 쓴 AI 친구의 글을 올리기 전에 모양과 규칙을 확인한다(012 research R15, FR-025). 어기면 [CompanionPostFailed]다.
 * 그 답은 올리지 않고 다시 청한다.
 *
 * - 비어 있지 않고 [MAX_LENGTH]자(그래핌) 이하다. 사람의 글과 같은 길이다.
 * - 한국어다. 글자 가운데 한글이 절반을 넘는다.
 * - 따옴표로 감싸거나 "제목:" 같은 머리말을 붙이지 않았다.
 * - 주소, 전화번호, 메일이 없다. 진단과 처방, 약과 치료의 권유가 없다.
 * - 죽음이나 자해를 떠올리게 하는 말이 없다. 지어 쓴 글이 위기 글처럼 읽히면 안 된다.
 * - 사람이라고 내세우지 않는다. 회원이나 다른 글을 가리키지 않는다. 널리 알려진 회사의 이름을 쓰지 않는다.
 *
 * 댓글의 검증기와 달리 "우리 팀", "제 팀장" 같은 일인칭 경험은 막지 않는다. 글은 지어 쓴 일인칭 이야기이고, 화면의
 * AI 친구 표시와 안내가 그것을 밝힌다(012 spec Assumptions). 규칙 검사는 낱말 목록이라 다 잡지 못한다. 프롬프트가 먼저
 * 막고, 실제 모델의 글을 사람이 읽어 확인한다(quickstart). 잘못 걸러도 글 하나를 다시 청할 뿐이라 넓게 잡는다.
 */
object CompanionPostValidator {
    /** 사람의 글과 같은 상한(post의 `Post.CONTENT_MAX_LENGTH`). ai 모듈은 post를 모르므로 여기에 적는다. */
    const val MAX_LENGTH = 500

    /** 죽음이나 자해를 떠올리게 하는 말. 키워드 위험 판정보다 넓게 잡는다. */
    private val DEATH =
        Regex("죽고\\s*싶|죽어\\s*버리|죽을\\s*것\\s*같|죽겠|자살|자해|사라지고\\s*싶|없어지고\\s*싶|극단적|목숨|살기\\s*싫|유서")

    /** 사람이라고 내세우는 말. AI 친구의 글은 AI가 지어 쓴 것이다. */
    private val HUMAN_CLAIM =
        Regex("(저는|나는|전|난)\\s*(진짜\\s*)?사람|(AI|인공지능|에이아이|봇)[가이은는]?\\s*아니|실제로\\s*겪은|실화", RegexOption.IGNORE_CASE)

    /** 회원이나 다른 글을 가리키는 말. 회원의 글을 재료로 삼지 않는다. */
    private val REFERRING = Regex("회원님|회원분|다른\\s*글|어떤\\s*분[이의]\\s*(쓴|올린)|님의\\s*글|님\\s*글|댓글에서|피드에서\\s*본")

    /** 널리 알려진 회사의 이름과 회사 이름에 붙는 말. 실제 회사가 드러나는 글을 쓰지 않는다. */
    private val COMPANY =
        Regex(
            "삼성|현대차|현대자동차|엘지|\\bLG\\b|\\bSK\\b|에스케이|네이버|카카오|쿠팡|배달의\\s*민족|배민|당근마켓|넥슨|엔씨소프트|" +
                "롯데|신세계|한화|포스코|\\bKT\\b|구글|애플|아마존|마이크로소프트|테슬라|㈜|\\(주\\)|주식회사",
            RegexOption.IGNORE_CASE,
        )

    fun validated(raw: String?): String {
        val post = raw?.trim().orEmpty()
        val broken =
            post.isEmpty() ||
                Grapheme.count(post, MAX_LENGTH + 1) > MAX_LENGTH ||
                !GeneratedTextRules.mostlyHangul(post) ||
                GeneratedTextRules.WRAPPED.matches(post) ||
                GeneratedTextRules.HEADER.containsMatchIn(post) ||
                GeneratedTextRules.hasContact(post) ||
                GeneratedTextRules.MEDICAL.containsMatchIn(post) ||
                DEATH.containsMatchIn(post) ||
                HUMAN_CLAIM.containsMatchIn(post) ||
                REFERRING.containsMatchIn(post) ||
                COMPANY.containsMatchIn(post)
        if (broken) throw CompanionPostFailed(INVALID_RESPONSE)
        return post
    }
}
