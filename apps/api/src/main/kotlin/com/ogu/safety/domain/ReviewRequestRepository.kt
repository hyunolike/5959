package com.ogu.safety.domain

import com.ogu.post.ContentType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** `review_request` 저장소(005 research R11). 대상마다 요청은 하나다. */
@Repository
class ReviewRequestRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 요청을 넣는다. 이 대상에 이미 요청이 있으면(닫힌 것도) 아무것도 하지 않고 null을 돌려준다. */
    fun insertIfAbsent(request: NewReviewRequest): Long? =
        jdbcClient
            .sql(
                """
                insert into review_request (target_type, target_id, post_id, requester_id, status, created_at)
                values (:type, :targetId, :postId, :requesterId, 'PENDING', :now)
                on conflict on constraint review_request_target_key do nothing
                returning id
                """.trimIndent(),
            ).param("type", request.type.name)
            .param("targetId", request.targetId)
            .param("postId", request.postId)
            .param("requesterId", request.requesterId)
            .param("now", Timestamp.from(request.createdAt))
            .query(Long::class.java)
            .optional()
            .orElse(null)

    fun find(id: Long): ReviewRequestRow? =
        jdbcClient
            .sql("select id, target_type, target_id, post_id, requester_id from review_request where id = :id")
            .param("id", id)
            .query { rs, _ ->
                ReviewRequestRow(
                    id = rs.getLong("id"),
                    type = ContentType.valueOf(rs.getString("target_type")),
                    targetId = rs.getLong("target_id"),
                    postId = rs.getLong("post_id"),
                    requesterId = rs.getLong("requester_id"),
                )
            }.optional()
            .orElse(null)

    /** 열린 요청을 [status]로 닫는다. 이미 닫혀 있으면 false다. 겹친 요청 가운데 하나만 true를 받는다. */
    fun close(
        id: Long,
        status: String,
        now: Instant,
    ): Boolean =
        jdbcClient
            .sql("update review_request set status = :status, closed_at = :now where id = :id and status = 'PENDING'")
            .param("status", status)
            .param("now", Timestamp.from(now))
            .param("id", id)
            .update() == 1

    /** 대상의 열린 요청을 [status]로 닫는다. 숨김이 풀렸을 때 쓴다. */
    fun closeOpenByTarget(
        type: ContentType,
        targetId: Long,
        status: String,
        now: Instant,
    ): Int =
        jdbcClient
            .sql(
                """
                update review_request set status = :status, closed_at = :now
                where target_type = :type and target_id = :targetId and status = 'PENDING'
                """.trimIndent(),
            ).param("status", status)
            .param("now", Timestamp.from(now))
            .param("type", type.name)
            .param("targetId", targetId)
            .update()

    /** 지워진 글과 그 글에 달린 댓글의 열린 요청을 닫는다. */
    fun closeOpenByPost(
        postId: Long,
        status: String,
        now: Instant,
    ): Int =
        jdbcClient
            .sql(
                """
                update review_request set status = :status, closed_at = :now
                where post_id = :postId and status = 'PENDING'
                """.trimIndent(),
            ).param("status", status)
            .param("now", Timestamp.from(now))
            .param("postId", postId)
            .update()
}

data class NewReviewRequest(
    val type: ContentType,
    val targetId: Long,
    val postId: Long,
    val requesterId: Long,
    val createdAt: Instant,
)

data class ReviewRequestRow(
    val id: Long,
    val type: ContentType,
    val targetId: Long,
    val postId: Long,
    val requesterId: Long,
)
