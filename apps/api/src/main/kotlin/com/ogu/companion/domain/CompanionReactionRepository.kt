package com.ogu.companion.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/**
 * 맡은 반응 기록 하나. [targetCount]는 이 글에 반응할 AI 친구의 수이고 [doneCount]는 지금까지 반응한 수다. [attempts]는
 * 지금 차례인 친구에게 댓글을 청한 횟수다.
 */
data class Reaction(
    val id: Long,
    val postId: Long,
    val postCreatedAt: Instant,
    val attempts: Int,
    val targetCount: Int = 1,
    val doneCount: Int = 0,
) {
    val full: Boolean get() = doneCount >= targetCount
}

/**
 * `companion_reaction` 저장소(009, 012 data-model.md). 글 하나에 행 하나다. 친구 한 명의 반응은
 * [ReactionEntryRepository]가 한 행씩 적는다. 본문과 댓글은 담지 않는다. 닫는 쿼리는 모두 `status = 'WAITING'`일 때만 바꿔, 맡은 뒤 다른
 * 실행기가 먼저 닫았으면 아무것도 하지 않는다.
 */
@Repository
class CompanionReactionRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 글의 반응 기록을 만든다. 처음 볼 때는 글이 올라온 때로 둔다(기다리는 시간은 맡을 때 설정으로 본다). 반응할 친구의
     * 수([targetCount])는 여기서 한 번 적고 바꾸지 않는다(012 FR-002). 같은 글의 기록이 이미 있으면 아무것도 하지 않는다.
     */
    fun enroll(
        postId: Long,
        postCreatedAt: Instant,
        targetCount: Int,
        now: Instant,
    ) {
        jdbcClient
            .sql(
                """
                insert into companion_reaction
                  (post_id, post_created_at, status, next_attempt_at, created_at, target_count)
                values (:postId, :postCreatedAt, 'WAITING', :postCreatedAt, :now, :targetCount)
                on conflict on constraint companion_reaction_post_key do nothing
                """.trimIndent(),
            ).param("postId", postId)
            .param("postCreatedAt", Timestamp.from(postCreatedAt))
            .param("now", Timestamp.from(now))
            .param("targetCount", targetCount)
            .update()
    }

    /**
     * 차례가 된 기록 하나를 잠근다: 올라온 지 기다리는 시간이 지났고([createdBefore]) 다시 볼 때가 됐다. [postId]를 주면
     * 그 글만 본다. 다른 실행기가 잡고 있으면 건너뛴다. 다시 볼 때가 먼저 된 글부터 잡는다. 처음에는 올라온 순서이고,
     * 판정을 기다리느라 미뤄 둔 글은 뒤로 간다. 올라온 순서로만 잡으면 판정이 오래 끝나지 않는 글 몇 개가 뒤의 글을 막는다.
     */
    fun lockDue(
        now: Instant,
        createdBefore: Instant,
        postId: Long? = null,
    ): Reaction? =
        jdbcClient
            .sql(
                """
                select id, post_id, post_created_at, attempts, target_count, done_count from companion_reaction
                where status = 'WAITING' and post_created_at <= :createdBefore and next_attempt_at <= :now
                  ${if (postId != null) "and post_id = :postId" else ""}
                order by next_attempt_at, post_created_at
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
                    targetCount = rs.getInt("target_count"),
                    doneCount = rs.getInt("done_count"),
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

    /**
     * 이 글에 반응한 수를 친구별 반응의 수로 맞추고 그 수를 돌려준다. 그래서 같은 반응을 두 번 적어도 수가 늘지 않는다.
     * 처음 남긴 댓글이면 기록의 댓글로 남긴다. 맡은 뒤 다른 실행기가 아무도 반응하지 않은 것으로 닫아 둔 기록이면 반응한
     * 것으로 바로잡는다. 친구별 반응을 넣은 것([ReactionEntryRepository.insert])과 같은 트랜잭션에서 부른다.
     */
    fun syncDone(
        id: Long,
        commentId: Long,
    ): Int =
        jdbcClient
            .sql(
                """
                update companion_reaction r
                set done_count = least(r.target_count, e.total),
                    comment_id = coalesce(r.comment_id, :commentId),
                    status = case when r.status = 'SKIPPED' then 'DONE' else r.status end
                from (select count(*) as total from companion_reaction_entry where reaction_id = :id) e
                where r.id = :id
                returning r.done_count
                """.trimIndent(),
            ).param("commentId", commentId)
            .param("id", id)
            .query(Int::class.java)
            .single()

    /** 다음 친구의 차례를 잡는다(012 research R3). 시도 횟수는 친구 한 명의 차례 안에서만 세므로 0으로 되돌린다. */
    fun scheduleNext(
        id: Long,
        nextAttemptAt: Instant,
    ) {
        jdbcClient
            .sql(
                """
                update companion_reaction set attempts = 0, last_error = null, next_attempt_at = :next
                where id = :id and status = 'WAITING'
                """.trimIndent(),
            ).param("next", Timestamp.from(nextAttemptAt))
            .param("id", id)
            .update()
    }

    /**
     * 기록을 닫는다. 한 명 이상 반응했으면 `DONE`, 아무도 반응하지 않았으면 `SKIPPED`다(012 research R4). [reason]은 정해진
     * 수를 채우지 못하고 멈춘 까닭이고, 다 채워서 닫을 때는 null이다. 까닭 없이는 한 명 이상 반응한 기록만 닫힌다.
     * [errorKind]가 있으면 마지막 실패의 분류로 남긴다.
     */
    fun close(
        id: Long,
        reason: SkipReason?,
        now: Instant,
        errorKind: String? = null,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update companion_reaction
                set status = case when done_count > 0 then 'DONE' else 'SKIPPED' end,
                    skip_reason = :reason, next_attempt_at = null, completed_at = :now,
                    last_error = case when :finished then null else coalesce(:kind, last_error) end
                where id = :id and status = 'WAITING' and (not :finished or done_count > 0)
                """.trimIndent(),
            ).param("reason", reason?.name)
            .param("finished", reason == null)
            .param("now", Timestamp.from(now))
            .param("kind", errorKind)
            .param("id", id)
            .update() == 1

    /**
     * [createdBefore]까지 올라온, 아직 기다리는 기록을 한 문장으로 닫는다(`EXPIRED`). 닫은 수를 돌려준다. 기능이 오래 꺼져
     * 있었으면 기다리는 기록이 쌓여 있다. 하나씩 맡아 닫으면 새 글이 그 뒤에서 기다린다. 다른 실행기가 맡아 처리 중인
     * 기록과 뒤따르는 친구의 차례를 기다리는 기록(다음 차례가 미뤄져 있다)은 건드리지 않는다. 뒤의 것은 차례가 됐을 때
     * 맡으면서 닫는다.
     */
    fun expireOlderThan(
        createdBefore: Instant,
        now: Instant,
    ): Int =
        jdbcClient
            .sql(
                """
                update companion_reaction
                set status = case when done_count > 0 then 'DONE' else 'SKIPPED' end,
                    skip_reason = 'EXPIRED', next_attempt_at = null, completed_at = :now
                where status = 'WAITING' and post_created_at <= :createdBefore and next_attempt_at <= :now
                """.trimIndent(),
            ).param("now", Timestamp.from(now))
            .param("createdBefore", Timestamp.from(createdBefore))
            .update()
}

/**
 * `companion_reaction_entry` 저장소(012 data-model.md). 한 글에 AI 친구 한 명이 남긴 반응 한 건이 한 행이다. 댓글의 번호만
 * 담고 내용은 담지 않는다.
 */
@Repository
class ReactionEntryRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 친구 한 명이 남긴 반응을 적는다. 같은 글에 같은 친구의 기록이 이미 있으면 다시 넣지 않는다. 한 글에 같은 친구는 한 번을
     * 지키는 것이 이 유일 제약이다(012 FR-005, research R1).
     */
    fun insert(
        reaction: Reaction,
        companionId: Int,
        commentId: Long,
        now: Instant,
    ) {
        jdbcClient
            .sql(
                """
                insert into companion_reaction_entry (reaction_id, post_id, companion_id, comment_id, created_at)
                values (:reactionId, :postId, :companionId, :commentId, :now)
                on conflict on constraint companion_reaction_entry_post_companion_key do nothing
                """.trimIndent(),
            ).param("reactionId", reaction.id)
            .param("postId", reaction.postId)
            .param("companionId", companionId)
            .param("commentId", commentId)
            .param("now", Timestamp.from(now))
            .update()
    }

    /**
     * 친구마다 가장 최근에 남긴 반응의 번호. 반응한 적이 없는 친구는 결과에 없다. 남긴 시각이 아니라 번호로 견줘,
     * 인스턴스의 시계가 조금 달라도 차례가 흐트러지지 않는다.
     */
    fun lastByCompanion(): Map<Int, Long> =
        jdbcClient
            .sql("select companion_id, max(id) as last_id from companion_reaction_entry group by companion_id")
            .query { rs, _ -> rs.getInt("companion_id") to rs.getLong("last_id") }
            .list()
            .toMap()
}
