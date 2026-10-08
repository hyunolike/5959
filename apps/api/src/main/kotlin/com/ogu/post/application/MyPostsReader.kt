package com.ogu.post.application

import com.ogu.post.PostPage
import com.ogu.post.domain.Visibility
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * 마이페이지 "내가 쓴 글" 한 쪽(004 research R12). `id DESC` 키셋이고 부분 인덱스 posts_author_live_idx를 탄다.
 * 작성자 자신의 목록이라 숨긴 글도 담는다(005 research R5). 커서는 피드 최신순과 같은 [PostCursor]다(글 ID만 쓴다). 다음 쪽이 있는지는 한 개 더 읽어 확인한다.
 */
@Component
class MyPostsReader(
    private val jdbcClient: JdbcClient,
) {
    fun read(
        authorId: Long,
        cursor: String?,
        size: Int,
    ): PostPage {
        requirePageSize(size)
        val cursorId = cursor?.let(PostCursor::decode)?.postId
        val rows =
            jdbcClient
                .sql(
                    """
                    select $POST_COLUMNS,
                           exists (select 1 from post_likes l where l.post_id = p.id and l.member_id = :authorId) as liked_by_me
                    from posts p
                    where p.author_id = :authorId and ${Visibility.notDeleted("p")}
                      ${if (cursorId != null) "and p.id < :cursorId" else ""}
                    order by p.id desc
                    limit :limit
                    """.trimIndent(),
                ).param("authorId", authorId)
                .param("limit", size + 1)
                .apply { if (cursorId != null) param("cursorId", cursorId) }
                .query { rs, _ -> rs.toPostPageItem() }
                .list()

        val items = rows.take(size)
        val nextCursor =
            if (rows.size > size) items.last().post.let { PostCursor(it.likeCount, it.postId).encode() } else null
        return PostPage(items, nextCursor)
    }
}
