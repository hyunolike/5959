package com.ogu.post.application

import com.ogu.post.MyComment
import com.ogu.post.MyCommentPage
import com.ogu.shared.text.Grapheme
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * 마이페이지 "내 댓글" 한 쪽(004 research R12). 살아 있는 글의 살아 있는 댓글과 답글을 `id DESC` 키셋으로 읽고
 * 부분 인덱스 comments_author_live_idx를 탄다. 글 본문은 앞 50글자만 싣는다. 쿼리 한 번이다.
 */
@Component
class MyCommentsReader(
    private val jdbcClient: JdbcClient,
) {
    fun read(
        authorId: Long,
        cursor: String?,
        size: Int,
    ): MyCommentPage {
        requirePageSize(size)
        val cursorId = cursor?.let(CommentCursor::decode)
        val rows =
            jdbcClient
                .sql(
                    """
                    select c.id, c.post_id, c.content, c.parent_id is not null as is_reply, c.created_at,
                           p.content as post_content
                    from comments c
                        join posts p on p.id = c.post_id and p.deleted_at is null
                    where c.author_id = :authorId and c.deleted_at is null
                      ${if (cursorId != null) "and c.id < :cursorId" else ""}
                    order by c.id desc
                    limit :limit
                    """.trimIndent(),
                ).param("authorId", authorId)
                .param("limit", size + 1)
                .apply { if (cursorId != null) param("cursorId", cursorId) }
                .query { rs, _ ->
                    MyComment(
                        commentId = rs.getLong("id"),
                        postId = rs.getLong("post_id"),
                        postContentPreview = Grapheme.take(rs.getString("post_content"), POST_PREVIEW_LENGTH),
                        content = rs.getString("content"),
                        isReply = rs.getBoolean("is_reply"),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                    )
                }.list()

        val items = rows.take(size)
        val nextCursor = if (rows.size > size) CommentCursor.encode(items.last().commentId) else null
        return MyCommentPage(items, nextCursor)
    }
}
