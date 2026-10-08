package com.ogu.post

/**
 * safety 모듈이 글과 댓글을 판정하고 숨기고 푸는 데 쓰는 파사드(005 research R1). 숨김 상태와 위험 단계는 post의 열이라
 * post만 쓴다. 다른 모듈은 이 파사드를 쓰지 않는다.
 */
interface PostModerationApi {
    /**
     * 지우지 않은 대상의 원문과 작성자. 지웠거나(댓글이면 그 글을 지웠어도) 없으면 null이다. 숨긴 것도 돌려준다.
     * 같은 트랜잭션에서 방금 쓴 행도 보인다.
     */
    fun contentOf(
        type: ContentType,
        id: Long,
    ): ModerationTarget?

    /** 여러 대상을 한 번에 읽는다. 운영자 조회가 쓴다. 지운 것은 결과에서 빠진다. 종류마다 쿼리 한 번이다. */
    fun contentsOf(targets: Collection<Pair<ContentType, Long>>): Map<Pair<ContentType, Long>, ModerationTarget>

    /** 가장 최근 판정의 단계 이름(`NONE`, `CONCERN`, `CRISIS`)을 적는다. */
    fun markRisk(
        type: ContentType,
        id: Long,
        level: String,
    )

    /** 작성자가 재검토를 요청했다고 적는다. */
    fun markReviewRequested(
        type: ContentType,
        id: Long,
    )

    /**
     * 다른 회원에게 보이지 않게 숨긴다. [reason]은 `RISK`나 `OPERATOR`다. 이미 숨겨져 있거나 지웠으면 false다.
     * 글이나 댓글 행을 UPDATE 한 문장으로 바꾸므로 겹친 요청 가운데 하나만 true를 받는다.
     */
    fun hide(
        type: ContentType,
        id: Long,
        reason: String,
    ): Boolean

    /** 숨김을 푼다. 숨겨져 있지 않거나 지웠으면 false다. 재검토 요청 표지도 함께 지운다. */
    fun unhide(
        type: ContentType,
        id: Long,
    ): Boolean

    /** ID가 [afterId]보다 큰 지우지 않은 대상을 ID 순으로 [limit]개 읽는다. 이미 있는 글을 한 번 훑는 작업이 쓴다. */
    fun scan(
        type: ContentType,
        afterId: Long,
        limit: Int,
    ): List<ModerationTarget>
}
