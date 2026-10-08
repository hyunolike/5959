package com.ogu.post.application

import com.ogu.post.MyCommentPage
import com.ogu.post.PostActivityApi
import com.ogu.post.PostPage
import com.ogu.post.PostRef
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PostActivityQueryService(
    private val jdbcClient: JdbcClient,
    private val myPostsReader: MyPostsReader,
    private val myCommentsReader: MyCommentsReader,
    private val likedPostsReader: LikedPostsReader,
) : PostActivityApi {
    override fun pageByAuthor(
        authorId: Long,
        cursor: String?,
        size: Int,
    ): PostPage = myPostsReader.read(authorId, cursor, size)

    override fun pageCommentsByAuthor(
        authorId: Long,
        cursor: String?,
        size: Int,
    ): MyCommentPage = myCommentsReader.read(authorId, cursor, size)

    override fun pageLikedBy(
        memberId: Long,
        cursor: String?,
        size: Int,
    ): PostPage = likedPostsReader.read(memberId, cursor, size)

    override fun liveRefsByAuthor(authorId: Long): List<PostRef> =
        jdbcClient
            .sql("select id, created_at from posts where author_id = :authorId and deleted_at is null")
            .param("authorId", authorId)
            .query { rs, _ -> PostRef(rs.getLong("id"), rs.getTimestamp("created_at").toInstant()) }
            .list()

    override fun liveIds(postIds: Collection<Long>): Set<Long> {
        if (postIds.isEmpty()) return emptySet()
        return jdbcClient
            .sql("select id from posts where id in (:postIds) and deleted_at is null")
            .param("postIds", postIds.toSet())
            .query(Long::class.java)
            .list()
            .filterNotNull()
            .toSet()
    }
}
