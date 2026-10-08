package com.ogu.post.application

import com.ogu.post.PostPage
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Timestamp

/**
 * 마이페이지 "공감한 글" 한 쪽(004 research R12). `post_likes`를 `(created_at DESC, post_id DESC)` 키셋으로 읽고
 * 인덱스 post_likes_member_created_idx를 탄다. 지운 글은 빼고, 취소한 공감은 행이 없어 빠진다. 다시 공감하면 새 행이라
 * 다시 공감한 시각 자리에 온다. 모두 내가 공감한 글이므로 공감 여부는 언제나 참이다.
 */
@Component
class LikedPostsReader(
    private val jdbcClient: JdbcClient,
) {
    fun read(
        memberId: Long,
        cursor: String?,
        size: Int,
    ): PostPage {
        requirePageSize(size)
        val decoded = cursor?.let(LikedPostsCursor::decode)
        val after = if (decoded != null) AFTER_CURSOR else ""
        val rows =
            jdbcClient
                .sql(
                    """
                    select $POST_COLUMNS, true as liked_by_me, l.created_at as liked_at
                    from post_likes l
                        join posts p on p.id = l.post_id and p.deleted_at is null
                    where l.member_id = :memberId
                      $after
                    order by l.created_at desc, l.post_id desc
                    limit :limit
                    """.trimIndent(),
                ).param("memberId", memberId)
                .param("limit", size + 1)
                .apply {
                    if (decoded != null) {
                        param("cursorLikedAt", Timestamp.from(decoded.likedAt))
                        param("cursorPostId", decoded.postId)
                    }
                }.query { rs, _ -> rs.toPostPageItem() to rs.getTimestamp("liked_at").toInstant() }
                .list()

        val page = rows.take(size)
        val nextCursor =
            if (rows.size > size) {
                page.last().let { (item, likedAt) -> LikedPostsCursor(likedAt, item.post.postId).encode() }
            } else {
                null
            }
        return PostPage(page.map { it.first }, nextCursor)
    }

    private companion object {
        const val AFTER_CURSOR =
            "and (l.created_at, l.post_id) < (cast(:cursorLikedAt as timestamptz), :cursorPostId)"
    }
}
