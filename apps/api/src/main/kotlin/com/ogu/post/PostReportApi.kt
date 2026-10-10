package com.ogu.post

import java.time.Instant

/**
 * 주간 리포트가 기간으로 읽는 파사드(008 research R5). 기간은 [from]부터(포함) [until] 전까지(제외)다. 본문은 주지 않는다.
 */
interface PostReportApi {
    /**
     * 기간 안에 지우지 않은 글을 하나 이상 쓴 회원의 번호. 번호가 [afterAuthorId]보다 큰 회원을 번호 순으로 [limit]명 준다.
     * 숨겨진 글도 센다(작성자의 한 주다).
     */
    fun authorIdsBetween(
        from: Instant,
        until: Instant,
        afterAuthorId: Long,
        limit: Int,
    ): List<Long>

    /** [authorId]가 기간 안에 쓴 지우지 않은 글. 쓴 순서다. 숨겨진 글도 들어 있다. */
    fun postsBetween(
        authorId: Long,
        from: Instant,
        until: Instant,
    ): List<ReportPostRef>

    /**
     * [authorId]의 지우지 않은 글(언제 쓴 글이든)에 기간 안에 다른 회원이 남긴 공감과 댓글의 수. 지운 댓글과 숨겨진 댓글은
     * 세지 않는다.
     */
    fun receivedBetween(
        authorId: Long,
        from: Instant,
        until: Instant,
    ): ReceivedCounts
}

data class ReportPostRef(
    val postId: Long,
    val createdAt: Instant,
    /** `NONE`, `CONCERN`, `CRISIS`. */
    val riskLevel: String,
)

data class ReceivedCounts(
    val likes: Int,
    val comments: Int,
)
