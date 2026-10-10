package com.ogu.recommend.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** 이미 있는 글을 임베딩 대상에 올리는 데 쓰는 `post_embedding` 쿼리(007 research R7). */
@Repository
class EmbeddingBackfillRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 이미 있는 글에 기다리는 행을 만든다. 행이 있으면 건드리지 않는다. 만든 수를 돌려준다. */
    fun requestIfAbsent(
        postId: Long,
        authorId: Long,
        now: Instant,
    ): Int =
        jdbcClient
            .sql(
                """
                insert into post_embedding (post_id, author_id, status, next_attempt_at, requested_at)
                values (:postId, :authorId, 'PENDING', :now, :now)
                on conflict (post_id) do nothing
                """.trimIndent(),
            ).param("postId", postId)
            .param("authorId", authorId)
            .param("now", Timestamp.from(now))
            .update()

    /** 옛 모델로 만든 값이 남은 행을 다시 기다리게 한다(FR-015). 바꾼 수를 돌려준다. */
    fun requeueOtherModels(
        model: String,
        now: Instant,
        limit: Int,
    ): Int =
        jdbcClient
            .sql(
                """
                update post_embedding
                set status = 'PENDING', requested_seq = requested_seq + 1, attempts = 0,
                    next_attempt_at = :now, requested_at = :now, last_error = null, completed_at = null
                where post_id in (
                    select post_id from post_embedding
                    where status = 'DONE' and model <> :model
                    order by post_id
                    limit :limit
                    for update skip locked
                )
                """.trimIndent(),
            ).param("model", model)
            .param("now", Timestamp.from(now))
            .param("limit", limit)
            .update()

    /** 준 ID들 가운데 행이 이미 있는 것. */
    fun existingIds(postIds: Collection<Long>): Set<Long> {
        if (postIds.isEmpty()) return emptySet()
        return jdbcClient
            .sql("select post_id from post_embedding where post_id in (:ids)")
            .param("ids", postIds)
            .query { rs, _ -> rs.getLong("post_id") }
            .list()
            .toSet()
    }
}
