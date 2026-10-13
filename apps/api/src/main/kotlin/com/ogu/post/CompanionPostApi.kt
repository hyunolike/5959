package com.ogu.post

import java.time.Instant

/**
 * AI 친구(companion 모듈)가 고민 글을 쓰는 파사드(012 research R11). 사람의 글쓰기와 같은 저장, 같은 이벤트를 타는 얇은
 * 길이다. 다른 모듈은 이 파사드를 쓰지 않는다. 글을 고치거나 지우는 길은 없다(012 FR-020).
 */
interface CompanionPostApi {
    /**
     * [memberId]의 이름으로 글을 저장하고 새 글의 번호를 돌려준다. [memberId]는 AI 회원이어야 한다. 사람 회원의 이름으로는
     * 이 길로 쓰지 못한다. 본문의 규칙(1~500자)은 사람의 글과 같고, 어기면 같은 예외가 나간다. 직군과 경력을 남기지 않고
     * 작성 제한을 타지 않는다(하루의 수는 부르는 쪽이 센다). 사람의 글과 같은 이벤트가 나가 감정 분석, 위험 판정, 임베딩이
     * 따라온다.
     */
    fun write(
        memberId: Long,
        content: String,
        commentTone: CommentTone,
    ): Long

    /**
     * 다른 회원에게 보이는 가장 새 글이 올라온 때. 사람의 글과 AI 친구의 글을 가리지 않는다. 지웠거나 숨긴 글은 세지 않는다.
     * 보이는 글이 하나도 없으면 null이다. "한동안 새 글이 없었는가"를 볼 때 쓴다(012 FR-021).
     */
    fun latestVisiblePostAt(): Instant?

    /**
     * [memberId]가 [since]부터 쓴 글 가운데 가장 먼저 것의 번호. 없으면 null이다. 지웠거나 숨긴 글도 센다. 글을 저장한 뒤
     * 기록을 적기 전에 멈췄는지 볼 때 쓴다. 같은 글을 두 번 올리지 않게 한다.
     */
    fun findWrittenSince(
        memberId: Long,
        since: Instant,
    ): Long?
}
