package com.ogu.companion.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** 맡은 반응 기록 하나. [attempts]는 지금까지 AI에 댓글을 청한 횟수다. */
data class Reaction(
    val id: Long,
    val postId: Long,
    val postCreatedAt: Instant,
    val attempts: Int,
)

/**
 * `companion_reaction` 저장소(009 data-model.md). 글 하나에 행 하나다. 본문과 댓글은 담지 않는다. 닫는 쿼리는 모두
 * `status = 'WAITING'`일 때만 바꿔, 맡은 뒤 다른 실행기가 먼저 닫았으면 아무것도 하지 않는다.
 */
@Repository
class CompanionReactionRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 글의 반응 기록을 만든다. 처음 볼 때는 글이 올라온 때로 둔다(기다리는 시간은 맡을 때 설정으로 본다). 같은 글의 기록이
     * 이미 있으면 아무것도 하지 않는다. 한 글에 한 번을 지키는 것이 이 유일 제약이다(research R4).
     */
    fun enroll(
        postId: Long,
        postCreatedAt: Instant,
        now: Instant,
    ) {
        jdbcClient
            .sql(
                """
                insert into companion_reaction (post_id, post_created_at, status, next_attempt_at, created_at)
                values (:postId, :postCreatedAt, 'WAITING', :postCreatedAt, :now)
                on conflict on constraint companion_reaction_post_key do nothing
                """.trimIndent(),
            ).param("postId", postId)
            .param("postCreatedAt", Timestamp.from(postCreatedAt))
            .param("now", Timestamp.from(now))
            .update()
    }

    /**
     * 차례가 된 기록 하나를 잠근다: 올라온 지 기다리는 시간이 지났고([createdBefore]) 다시 볼 때가 됐다. [postId]를 주면
     * 그 글만 본다. 다른 실행기가 잡고 있으면 건너뛴다. 먼저 올라온 글부터 잡는다.
     */
    fun lockDue(
        now: Instant,
        createdBefore: Instant,
        postId: Long? = null,
    ): Reaction? =
        jdbcClient
            .sql(
                """
                select id, post_id, post_created_at, attempts from companion_reaction
                where status = 'WAITING' and post_created_at <= :createdBefore and next_attempt_at <= :now
                  ${if (postId != null) "and post_id = :postId" else ""}
                order by post_created_at
                limit 1
                for update skip locked
                """.trimIndent(),
            ).param("now", Timestamp.from(now))
            .param("createdBefore", Timestamp.from(createdBefore))
            .apply { if (postId != null) param("postId", postId) }
            .query { rs, _ ->
                Reaction(
                    id = rs.getLong("id"),
                    postId = rs.getLong("post_id"),
                    postCreatedAt = rs.getTimestamp("post_created_at").toInstant(),
                    attempts = rs.getInt("attempts"),
                )
            }.optional()
            .orElse(null)

    /** 다시 볼 때를 미룬다. 시도 횟수는 그대로다. 맡은 동안 다른 실행기가 잡지 않게 할 때와 판정을 기다릴 때 쓴다. */
    fun defer(
        id: Long,
        nextAttemptAt: Instant,
    ) {
        jdbcClient
            .sql("update companion_reaction set next_attempt_at = :next where id = :id and status = 'WAITING'")
            .param("next", Timestamp.from(nextAttemptAt))
            .param("id", id)
            .update()
    }

    /** AI에 댓글을 청한다고 적는다: 시도 횟수를 올리고, 청한 친구와 다음 차례를 적는다. */
    fun markAttempt(
        id: Long,
        companionId: Int,
        nextAttemptAt: Instant,
    ) {
        jdbcClient
            .sql(
                """
                update companion_reaction
                set attempts = attempts + 1, companion_id = :companionId, next_attempt_at = :next
                where id = :id and status = 'WAITING'
                """.trimIndent(),
            ).param("companionId", companionId)
            .param("next", Timestamp.from(nextAttemptAt))
            .param("id", id)
            .update()
    }

    /** 호출이 공급자에 나가지 않았다(회로가 열렸다). 방금 올린 시도 횟수를 되돌리고 까닭만 남긴다. */
    fun revertAttempt(
        id: Long,
        errorKind: String,
    ) {
        jdbcClient
            .sql(
                """
                update companion_reaction set attempts = greatest(attempts - 1, 0), last_error = :kind
                where id = :id and status = 'WAITING'
                """.trimIndent(),
            ).param("kind", errorKind)
            .param("id", id)
            .update()
    }

    /** 실패의 분류만 적는다. 다음 차례는 청할 때 이미 미뤄 두었다. */
    fun recordFailure(
        id: Long,
        errorKind: String,
    ) {
        jdbcClient
            .sql("update companion_reaction set last_error = :kind where id = :id and status = 'WAITING'")
            .param("kind", errorKind)
            .param("id", id)
            .update()
    }

    /** 댓글과 공감을 남겼다고 닫는다. */
    fun complete(
        id: Long,
        commentId: Long,
        now: Instant,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update companion_reaction
                set status = 'DONE', comment_id = :commentId, next_attempt_at = null, last_error = null,
                    completed_at = :now
                where id = :id and status = 'WAITING'
                """.trimIndent(),
            ).param("commentId", commentId)
            .param("now", Timestamp.from(now))
            .param("id", id)
            .update() == 1

    /** 반응하지 않고 닫는다. [errorKind]가 있으면 마지막 실패의 분류로 남긴다. */
    fun skip(
        id: Long,
        reason: SkipReason,
        now: Instant,
        errorKind: String? = null,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update companion_reaction
                set status = 'SKIPPED', skip_reason = :reason, next_attempt_at = null, completed_at = :now,
                    last_error = coalesce(:kind, last_error)
                where id = :id and status = 'WAITING'
                """.trimIndent(),
            ).param("reason", reason.name)
            .param("now", Timestamp.from(now))
            .param("kind", errorKind)
            .param("id", id)
            .update() == 1

    /**
     * 친구마다 가장 최근에 반응한 기록의 번호. 반응한 적이 없는 친구는 결과에 없다. 완료 시각이 아니라 기록 번호로 견줘,
     * 인스턴스의 시계가 조금 달라도 차례가 흐트러지지 않는다.
     */
    fun lastDoneByCompanion(): Map<Int, Long> =
        jdbcClient
            .sql(
                """
                select companion_id, max(id) as last_id from companion_reaction
                where status = 'DONE' and companion_id is not null
                group by companion_id
                """.trimIndent(),
            ).query { rs, _ -> rs.getInt("companion_id") to rs.getLong("last_id") }
            .list()
            .toMap()
}
