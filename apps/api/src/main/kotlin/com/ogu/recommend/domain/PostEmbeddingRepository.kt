package com.ogu.recommend.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** 맡은 시도 하나. [requestedSeq]와 [attempts]는 결과를 적을 때 그사이 달라지지 않았는지 보는 데 쓴다. */
data class EmbeddingClaim(
    val postId: Long,
    val requestedSeq: Int,
    val attempts: Int,
    val requestedAt: Instant,
)

/** 가까운 후보 하나. [distance]는 코사인 거리(0이면 같은 방향)다. */
data class SimilarCandidate(
    val postId: Long,
    val distance: Double,
)

/** 글 하나의 임베딩 상태. [usable]이면 지금 모델로 만든 값이 있어 가까운 글을 찾을 수 있다. */
data class EmbeddingState(
    val status: String,
    val usable: Boolean,
)

/**
 * `post_embedding` 저장소(007 data-model.md). 값은 pgvector의 `halfvec`이고 문자열(`[0.1,0.2,…]`)로 넘겨 변환한다.
 * 값 자체는 로그에 남기지 않는다.
 */
@Repository
class PostEmbeddingRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 글이 저장되거나 고쳐졌다. 행이 없으면 만들고, 있으면 요청 번호를 올려 다시 기다리게 한다. 이전 값은 그대로 둔다.
     */
    fun request(
        postId: Long,
        authorId: Long,
        now: Instant,
    ) {
        jdbcClient
            .sql(
                """
                insert into post_embedding (post_id, author_id, status, next_attempt_at, requested_at)
                values (:postId, :authorId, 'PENDING', :now, :now)
                on conflict (post_id) do update
                set status = 'PENDING', requested_seq = post_embedding.requested_seq + 1, attempts = 0,
                    next_attempt_at = :now, requested_at = :now, last_error = null, completed_at = null
                """.trimIndent(),
            ).param("postId", postId)
            .param("authorId", authorId)
            .param("now", Timestamp.from(now))
            .update()
    }

    /**
     * 시도할 차례인 행 하나를 잠근다. [postId]를 주면 그 글만 본다. 다른 실행기가 잡고 있으면 건너뛴다.
     * 나중에 요청된 글부터 잡는다. 이미 있는 글 1만 건이 밀려 있어도 방금 쓴 글의 재시도가 그 뒤에 서지 않는다(US4-AC2).
     */
    fun lockDue(
        now: Instant,
        postId: Long? = null,
    ): EmbeddingClaim? {
        val onlyPost = if (postId == null) "" else "and post_id = :postId"
        var spec =
            jdbcClient
                .sql(
                    """
                    select post_id, requested_seq, attempts, requested_at
                    from post_embedding
                    where status = 'PENDING' and next_attempt_at <= :now $onlyPost
                    order by requested_at desc
                    limit 1
                    for update skip locked
                    """.trimIndent(),
                ).param("now", Timestamp.from(now))
        if (postId != null) spec = spec.param("postId", postId)
        return spec
            .query { rs, _ ->
                EmbeddingClaim(
                    postId = rs.getLong("post_id"),
                    requestedSeq = rs.getInt("requested_seq"),
                    attempts = rs.getInt("attempts"),
                    requestedAt = rs.getTimestamp("requested_at").toInstant(),
                )
            }.optional()
            .orElse(null)
    }

    /** 맡았다고 적는다. 시도 횟수를 올리고 다음 차례를 미뤄, 호출이 끝나기 전에 다른 실행기가 맡지 않게 한다. */
    fun markAttempt(
        postId: Long,
        nextAttemptAt: Instant,
    ) {
        jdbcClient
            .sql("update post_embedding set attempts = attempts + 1, next_attempt_at = :next where post_id = :postId")
            .param("next", Timestamp.from(nextAttemptAt))
            .param("postId", postId)
            .update()
    }

    fun giveUp(
        postId: Long,
        now: Instant,
    ) {
        jdbcClient
            .sql("update post_embedding set status = 'GIVEN_UP', completed_at = :now where post_id = :postId")
            .param("now", Timestamp.from(now))
            .param("postId", postId)
            .update()
    }

    /**
     * 값을 적는다. 맡은 뒤 글이 고쳐졌으면(요청 번호가 달라졌으면) 아무것도 바꾸지 않고 false다. 늦게 온 이전 결과가
     * 새 요청을 덮지 않는다.
     */
    fun complete(
        claim: EmbeddingClaim,
        model: String,
        values: FloatArray,
        now: Instant,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update post_embedding
                set embedding = cast(:embedding as halfvec), model = :model, embedded_seq = requested_seq,
                    status = 'DONE', completed_at = :now, last_error = null
                where post_id = :postId and requested_seq = :seq and status = 'PENDING'
                """.trimIndent(),
            ).param("embedding", values.joinToString(prefix = "[", postfix = "]", separator = ","))
            .param("model", model)
            .param("now", Timestamp.from(now))
            .param("postId", claim.postId)
            .param("seq", claim.requestedSeq)
            .update() == 1

    fun recordFailure(
        claim: EmbeddingClaim,
        errorKind: String,
    ) {
        jdbcClient
            .sql(
                """
                update post_embedding set last_error = :error
                where post_id = :postId and requested_seq = :seq and status = 'PENDING'
                """.trimIndent(),
            ).param("error", errorKind)
            .param("postId", claim.postId)
            .param("seq", claim.requestedSeq)
            .update()
    }

    fun delete(postId: Long) {
        jdbcClient.sql("delete from post_embedding where post_id = :postId").param("postId", postId).update()
    }

    /** 글의 임베딩 상태. 행이 없으면 null이다. */
    fun stateOf(
        postId: Long,
        model: String,
    ): EmbeddingState? =
        jdbcClient
            .sql(
                """
                select status, (embedding is not null and model = :model) as usable
                from post_embedding where post_id = :postId
                """.trimIndent(),
            ).param("postId", postId)
            .param("model", model)
            .query { rs, _ -> EmbeddingState(rs.getString("status"), rs.getBoolean("usable")) }
            .optional()
            .orElse(null)

    /**
     * [postId]의 값과 가까운 글을 가까운 순서로 [limit]개 읽는다(research R5). 같은 모델로 끝난 글만 후보다.
     * [viewerId]가 쓴 글과 [postId] 자신은 뺀다. 숨겼거나 지운 글은 부르는 쪽이 `PostApi`로 거른다.
     */
    fun nearest(
        postId: Long,
        viewerId: Long,
        model: String,
        limit: Int,
    ): List<SimilarCandidate> =
        jdbcClient
            .sql(
                """
                select c.post_id, c.embedding <=> s.embedding as distance
                from post_embedding s
                    join post_embedding c
                        on c.model = s.model and c.status = 'DONE' and c.post_id <> s.post_id
                where s.post_id = :postId and s.embedding is not null and s.model = :model
                  and c.author_id <> :viewerId
                order by c.embedding <=> s.embedding
                limit :limit
                """.trimIndent(),
            ).param("postId", postId)
            .param("model", model)
            .param("viewerId", viewerId)
            .param("limit", limit)
            .query { rs, _ -> SimilarCandidate(rs.getLong("post_id"), rs.getDouble("distance")) }
            .list()
}
