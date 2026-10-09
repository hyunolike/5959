package com.ogu.post.domain

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/**
 * 댓글 공감(data-model.md `comment_likes`, PK (comment_id, member_id))과 댓글의 공감 수.
 * [PostLikeRepository]와 같은 규칙이다. 겹친 삭제가 먼저 커밋했으면 404 COMMENT_NOT_FOUND다.
 */
@Repository
class CommentLikeRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 새로 공감했으면 바뀐 공감 수, 이미 공감한 상태면 null. */
    fun add(
        commentId: Long,
        memberId: Long,
        now: Instant,
    ): Int? {
        val inserted =
            jdbcClient
                .sql(
                    """
                    insert into comment_likes (comment_id, member_id, created_at) values (:commentId, :memberId, :now)
                    on conflict on constraint comment_likes_pkey do nothing
                    """.trimIndent(),
                ).param("commentId", commentId)
                .param("memberId", memberId)
                .param("now", Timestamp.from(now))
                .update()
        return if (inserted == 1) addLikeCount(commentId, 1) else null
    }

    /** 공감을 지웠으면 바뀐 공감 수, 공감하지 않은 상태였으면 null. */
    fun remove(
        commentId: Long,
        memberId: Long,
    ): Int? {
        val deleted =
            jdbcClient
                .sql("delete from comment_likes where comment_id = :commentId and member_id = :memberId")
                .param("commentId", commentId)
                .param("memberId", memberId)
                .update()
        return if (deleted == 1) addLikeCount(commentId, -1) else null
    }

    /** [commentIds] 가운데 [memberId]가 공감한 댓글 ID. 쿼리 한 번이다. */
    fun likedCommentIds(
        memberId: Long,
        commentIds: Collection<Long>,
    ): Set<Long> {
        if (commentIds.isEmpty()) return emptySet()
        return jdbcClient
            .sql("select comment_id from comment_likes where member_id = :memberId and comment_id in (:ids)")
            .param("memberId", memberId)
            .param("ids", commentIds.toSet())
            .query(Long::class.java)
            .list()
            .filterNotNull()
            .toSet()
    }

    private fun addLikeCount(
        commentId: Long,
        delta: Int,
    ): Int =
        jdbcClient
            .sql(
                "update comments set like_count = like_count + :delta " +
                    "where id = :commentId and ${Visibility.VISIBLE} returning like_count",
            ).param("delta", delta)
            .param("commentId", commentId)
            .query(Int::class.java)
            .optional()
            // 앞서 살아 있는지 본 뒤 삭제가 먼저 커밋됐다. 예외로 트랜잭션을 되돌려 공감 행과 HP 반영이 남지 않게 한다.
            .orElseThrow { BusinessException(ErrorCode.COMMENT_NOT_FOUND) }
}
