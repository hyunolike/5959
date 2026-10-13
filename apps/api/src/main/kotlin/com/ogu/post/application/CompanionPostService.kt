package com.ogu.post.application

import com.ogu.post.CommentTone
import com.ogu.post.CompanionPostApi
import com.ogu.post.domain.Visibility
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

/** [CompanionPostApi]의 구현(012 research R11). 저장은 [PostService.createByAi]가 사람의 글과 같은 길로 한다. */
@Service
class CompanionPostService(
    private val posts: PostService,
    private val jdbcClient: JdbcClient,
) : CompanionPostApi {
    override fun write(
        memberId: Long,
        content: String,
        commentTone: CommentTone,
    ): Long = posts.createByAi(memberId, content, commentTone)

    @Transactional(readOnly = true)
    override fun latestVisiblePostAt(): Instant? =
        jdbcClient
            .sql("select max(created_at) from posts where ${Visibility.VISIBLE}")
            .query { rs, _ -> rs.getTimestamp(1)?.toInstant() }
            .optional()
            .orElse(null)

    @Transactional(readOnly = true)
    override fun findWrittenSince(
        memberId: Long,
        since: Instant,
    ): Long? =
        jdbcClient
            .sql("select min(id) from posts where author_id = :memberId and created_at >= :since")
            .param("memberId", memberId)
            .param("since", Timestamp.from(since))
            .query { rs, _ -> rs.getObject(1)?.let { (it as Number).toLong() } }
            .optional()
            .orElse(null)
}
