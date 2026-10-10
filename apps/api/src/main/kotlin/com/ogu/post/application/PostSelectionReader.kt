package com.ogu.post.application

import com.ogu.post.PostAuthorRef
import com.ogu.post.PostPage
import com.ogu.post.PostSelectionApi
import com.ogu.post.domain.Visibility
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** [PostSelectionApi]의 구현. 보이는 글의 조건은 피드와 같은 [Visibility]를 쓴다. 쿼리는 호출마다 한 번이다. */
@Service
@Transactional(readOnly = true)
class PostSelectionReader(
    private val jdbcClient: JdbcClient,
) : PostSelectionApi {
    override fun visibleIds(
        postIds: Collection<Long>,
        exceptAuthorId: Long,
    ): Set<Long> {
        if (postIds.isEmpty()) return emptySet()
        return jdbcClient
            .sql(
                """
                select p.id from posts p
                where p.id in (:postIds) and p.author_id <> :exceptAuthorId and ${Visibility.visible("p")}
                """.trimIndent(),
            ).param("postIds", postIds.toSet())
            .param("exceptAuthorId", exceptAuthorId)
            .query { rs, _ -> rs.getLong("id") }
            .list()
            .toSet()
    }

    override fun pageOf(
        postIds: List<Long>,
        viewerId: Long,
    ): PostPage {
        if (postIds.isEmpty()) return PostPage(emptyList(), nextCursor = null)
        val items =
            jdbcClient
                .sql(
                    """
                    select $POST_COLUMNS,
                           exists (select 1 from post_likes l
                                   where l.post_id = p.id and l.member_id = :viewerId) as liked_by_me
                    from posts p
                    where p.id in (:postIds) and ${Visibility.visible("p")}
                    """.trimIndent(),
                ).param("postIds", postIds.toSet())
                .param("viewerId", viewerId)
                .query { rs, _ -> rs.toPostPageItem() }
                .list()
                .associateBy { it.post.postId }
        return PostPage(postIds.mapNotNull(items::get), nextCursor = null)
    }

    override fun authorsAfter(
        afterId: Long,
        limit: Int,
    ): List<PostAuthorRef> =
        jdbcClient
            .sql(
                """
                select p.id, p.author_id from posts p
                where p.id > :afterId and ${Visibility.notDeleted("p")}
                order by p.id
                limit :limit
                """.trimIndent(),
            ).param("afterId", afterId)
            .param("limit", limit)
            .query { rs, _ -> PostAuthorRef(rs.getLong("id"), rs.getLong("author_id")) }
            .list()
}
