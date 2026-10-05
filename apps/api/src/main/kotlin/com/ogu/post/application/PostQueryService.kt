package com.ogu.post.application

import com.ogu.post.Attack
import com.ogu.post.AttackAction
import com.ogu.post.CommentSummary
import com.ogu.post.PostApi
import com.ogu.post.PostPage
import com.ogu.post.PostPageQuery
import com.ogu.post.PostSummary
import com.ogu.post.domain.Post
import com.ogu.post.domain.PostRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PostQueryService(
    private val postRepository: PostRepository,
    private val jdbcClient: JdbcClient,
    private val postPageReader: PostPageReader,
) : PostApi {
    override fun find(postId: Long): PostSummary? = postRepository.findByIdAndDeletedAtIsNull(postId)?.toSummary()

    override fun likedPostIds(
        memberId: Long,
        postIds: Collection<Long>,
    ): Set<Long> {
        if (postIds.isEmpty()) return emptySet()
        return jdbcClient
            .sql("select post_id from post_likes where member_id = :memberId and post_id in (:postIds)")
            .param("memberId", memberId)
            .param("postIds", postIds.toSet())
            .query(Long::class.java)
            .list()
            .filterNotNull()
            .toSet()
    }

    override fun page(query: PostPageQuery): PostPage = postPageReader.read(query)

    /**
     * 작성자를 뺀 회원의 살아 있는 글 공감, 회원별 살아 있는 댓글 하나(답글 포함, 감소는 글 하나에 한 번이라 대상은 글 ID),
     * 살아 있는 댓글에 받은 공감. 지운 글이면 비어 있다. 행동한 순서(공감, 댓글, 댓글 공감 각각 오래된 순)로 돌려준다.
     */
    override fun attacksSoFar(postId: Long): List<Attack> =
        jdbcClient
            .sql(ATTACKS_SO_FAR)
            .param("postId", postId)
            .query { rs, _ ->
                Attack(rs.getLong("member_id"), AttackAction.valueOf(rs.getString("action")), rs.getLong("target_id"))
            }.list()

    override fun findComment(commentId: Long): CommentSummary? =
        jdbcClient
            .sql(FIND_COMMENT)
            .param("commentId", commentId)
            .query { rs, _ ->
                CommentSummary(
                    postId = rs.getLong("post_id"),
                    authorId = rs.getLong("author_id"),
                    parentId = rs.getLong("parent_id").takeUnless { rs.wasNull() },
                    parentAuthorId = rs.getLong("parent_author_id").takeUnless { rs.wasNull() },
                )
            }.optional()
            .orElse(null)

    private fun Post.toSummary(): PostSummary =
        PostSummary(
            postId = id,
            authorId = authorId,
            authorJobRole = authorJobRole,
            authorCareerYear = authorCareerYear,
            content = content,
            commentTone = commentTone,
            likeCount = likeCount,
            commentCount = commentCount,
            createdAt = createdAt,
        )

    private companion object {
        /** 살아 있는 글의 살아 있는 댓글. 답글이면 원 댓글 주인도 함께 읽는다(원 댓글을 지우면 답글도 함께 지워진다). */
        val FIND_COMMENT =
            """
            select c.post_id, c.author_id, c.parent_id, parent.author_id as parent_author_id
            from comments c
                join posts p on p.id = c.post_id and p.deleted_at is null
                left join comments parent on parent.id = c.parent_id
            where c.id = :commentId and c.deleted_at is null
            """.trimIndent()

        val ATTACKS_SO_FAR =
            """
            with post as (select id, author_id from posts where id = :postId and deleted_at is null)
            select member_id, action, target_id
            from (
                select l.member_id, 'POST_LIKE' as action, l.post_id as target_id, 1 as kind, l.created_at, 0 as tie
                from post_likes l join post on post.id = l.post_id
                where l.member_id <> post.author_id
                union all
                select c.author_id, 'COMMENT', c.post_id, 2, min(c.created_at), min(c.id)
                from comments c join post on post.id = c.post_id
                where c.deleted_at is null and c.author_id <> post.author_id
                group by c.author_id, c.post_id
                union all
                select cl.member_id, 'COMMENT_LIKE', cl.comment_id, 3, cl.created_at, cl.comment_id
                from comment_likes cl
                    join comments c on c.id = cl.comment_id
                    join post on post.id = c.post_id
                where c.deleted_at is null and cl.member_id <> post.author_id
            ) attacks
            order by kind, created_at, tie, member_id
            """.trimIndent()
    }
}
