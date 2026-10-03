package com.ogu.post.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/**
 * 글 공감(data-model.md `post_likes`, PK (post_id, member_id))과 글의 공감 수. 취소는 행을 지운다.
 * 같은 회원이 동시에 두 번 공감해도 `ON CONFLICT DO NOTHING`이라 PK 위반 예외 없이 한쪽만 저장된다.
 * 공감 수는 동시 요청이 서로의 증감을 덮어쓰지 않도록 `SET like_count = like_count ± 1` 한 문장으로 바꾼다.
 */
@Repository
class PostLikeRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 새로 공감했으면 바뀐 공감 수, 이미 공감한 상태면 null. */
    fun add(
        postId: Long,
        memberId: Long,
        now: Instant,
    ): Int? {
        val inserted =
            jdbcClient
                .sql(
                    """
                    insert into post_likes (post_id, member_id, created_at) values (:postId, :memberId, :now)
                    on conflict on constraint post_likes_pkey do nothing
                    """.trimIndent(),
                ).param("postId", postId)
                .param("memberId", memberId)
                .param("now", Timestamp.from(now))
                .update()
        return if (inserted == 1) addLikeCount(postId, 1) else null
    }

    /** 공감을 지웠으면 바뀐 공감 수, 공감하지 않은 상태였으면 null. */
    fun remove(
        postId: Long,
        memberId: Long,
    ): Int? {
        val deleted =
            jdbcClient
                .sql("delete from post_likes where post_id = :postId and member_id = :memberId")
                .param("postId", postId)
                .param("memberId", memberId)
                .update()
        return if (deleted == 1) addLikeCount(postId, -1) else null
    }

    private fun addLikeCount(
        postId: Long,
        delta: Int,
    ): Int =
        jdbcClient
            .sql("update posts set like_count = like_count + :delta where id = :postId returning like_count")
            .param("delta", delta)
            .param("postId", postId)
            .query(Int::class.java)
            .single()
}
