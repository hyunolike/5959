package com.ogu.post.application

import com.ogu.post.Attack
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

    /** US3(T042)에서 구현한다. 그 전까지 몬스터 생성(MonsterFactory)은 소급 반영을 하지 않으므로 부르지 않는다. */
    override fun attacksSoFar(postId: Long): List<Attack> = throw UnsupportedOperationException(ATTACKS_NOT_READY)

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
        const val ATTACKS_NOT_READY = "attacksSoFar는 T042(US3)에서 구현한다."
    }
}
