package com.ogu.companion.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/** 맡은 글 기록 하나. AI 친구가 글을 쓰려 하는 일 한 건이다. [attempts]는 지금까지 AI에 글을 청한 횟수다. */
data class PostAttempt(
    val id: Long,
    val companionId: Int,
    val day: LocalDate,
    val topic: String,
    val commentTone: String,
    val attempts: Int,
    val createdAt: Instant,
)

/** AI 친구가 글을 쓰지 못하고 그만둔 까닭(012 data-model.md "그만둔 까닭"). 운영자가 기록에서 읽는다. */
enum class GiveUpReason {
    /** 쓰는 사이에 새 글이 올라왔다. */
    NOT_QUIET,

    /** 쓰는 사이에 글 쓰는 시간대를 벗어났다. */
    OFF_HOURS,

    /** 날짜가 바뀌었다. 오래 꺼져 있었다. */
    EXPIRED,

    /** 그날 AI에 글을 청한 횟수가 상한에 닿았다. */
    DAILY_LIMIT,

    /** AI가 정해 둔 횟수만큼 답하지 못했다. */
    FAILED,

    /** AI의 답이 정해 둔 횟수만큼 검사에 걸렸다. */
    REJECTED,
}

/** 하루(한국 시간)의 글 기록을 센 것. */
data class PostingDay(
    /** 그날 올린 글의 수. */
    val posted: Int,
    /** 그날 AI에 글을 청한 횟수. 실패한 시도도 든다. */
    val requests: Int,
    /** 그날 기록이 있는 친구. 올렸든 그만뒀든 쓰는 중이든 그날의 자리를 썼다. */
    val companionIds: Set<Int>,
)

/**
 * `companion_post` 저장소(012 data-model.md). AI 친구가 글을 쓰려 한 일 한 건이 한 행이다. 본문은 담지 않는다. 친구마다
 * 하루 한 행(`companion_post_companion_day_key`)이고 쓰는 중인 행은 하나(`companion_post_writing_key`)다. 닫는 쿼리는
 * 모두 `status = 'WRITING'`일 때만 바꾼다.
 */
@Repository
class CompanionPostRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 글 기록을 만든다. 그 친구의 그날 기록이 이미 있거나 쓰는 중인 기록이 이미 있으면 넣지 않고 false다. 여러 서버가 함께
     * 조건을 보고 넣어도 하나만 들어간다(012 research R12).
     */
    fun open(
        companionId: Int,
        day: LocalDate,
        topic: String,
        commentTone: String,
        now: Instant,
    ): Boolean =
        jdbcClient
            .sql(
                """
                insert into companion_post (companion_id, day, topic, comment_tone, status, next_attempt_at, created_at)
                values (:companionId, :day, :topic, :tone, 'WRITING', :now, :now)
                on conflict do nothing
                """.trimIndent(),
            ).param("companionId", companionId)
            .param("day", Date.valueOf(day))
            .param("topic", topic)
            .param("tone", commentTone)
            .param("now", Timestamp.from(now))
            .update() == 1

    /** 차례가 된 쓰는 중인 기록을 잠근다. 다른 실행기가 잡고 있거나 다시 볼 때가 아니면 null이다. */
    fun lockWriting(now: Instant): PostAttempt? =
        jdbcClient
            .sql(
                """
                select id, companion_id, day, topic, comment_tone, attempts, created_at from companion_post
                where status = 'WRITING' and next_attempt_at <= :now
                limit 1
                for update skip locked
                """.trimIndent(),
            ).param("now", Timestamp.from(now))
            .query { rs, _ ->
                PostAttempt(
                    id = rs.getLong("id"),
                    companionId = rs.getInt("companion_id"),
                    day = rs.getDate("day").toLocalDate(),
                    topic = rs.getString("topic"),
                    commentTone = rs.getString("comment_tone"),
                    attempts = rs.getInt("attempts"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }.optional()
            .orElse(null)

    /** 다시 볼 때를 미룬다. [counted]면 AI에 글을 청한다는 뜻이라 시도 횟수를 올린다. */
    fun defer(
        id: Long,
        nextAttemptAt: Instant,
        counted: Boolean = false,
    ) {
        jdbcClient
            .sql(
                """
                update companion_post
                set next_attempt_at = :next, attempts = attempts + case when :counted then 1 else 0 end
                where id = :id and status = 'WRITING'
                """.trimIndent(),
            ).param("next", Timestamp.from(nextAttemptAt))
            .param("counted", counted)
            .param("id", id)
            .update()
    }

    /**
     * 실패의 분류만 적는다. 다음 차례는 청할 때 이미 미뤄 두었다. [counted]가 아니면 호출이 공급자에 나가지 않은 것이라
     * 방금 올린 시도 횟수를 되돌린다.
     */
    fun recordFailure(
        id: Long,
        errorKind: String,
        counted: Boolean,
    ) {
        jdbcClient
            .sql(
                """
                update companion_post
                set last_error = :kind, attempts = greatest(attempts - case when :counted then 0 else 1 end, 0)
                where id = :id and status = 'WRITING'
                """.trimIndent(),
            ).param("kind", errorKind)
            .param("counted", counted)
            .param("id", id)
            .update()
    }

    /** 글을 올렸다고 닫는다. */
    fun posted(
        id: Long,
        postId: Long,
        now: Instant,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update companion_post
                set status = 'POSTED', post_id = :postId, next_attempt_at = null, last_error = null, completed_at = :now
                where id = :id and status = 'WRITING'
                """.trimIndent(),
            ).param("postId", postId)
            .param("now", Timestamp.from(now))
            .param("id", id)
            .update() == 1

    /** 쓰지 못하고 닫는다. [errorKind]가 있으면 마지막 실패의 분류로 남긴다. */
    fun giveUp(
        id: Long,
        reason: GiveUpReason,
        now: Instant,
        errorKind: String? = null,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update companion_post
                set status = 'GIVEN_UP', give_up_reason = :reason, next_attempt_at = null, completed_at = :now,
                    last_error = coalesce(:kind, last_error)
                where id = :id and status = 'WRITING'
                """.trimIndent(),
            ).param("reason", reason.name)
            .param("now", Timestamp.from(now))
            .param("kind", errorKind)
            .param("id", id)
            .update() == 1

    /** [day]의 기록을 센다. 쓰는 중인 기록이 하나뿐이라, 읽고 올리는 사이에 다른 실행기가 끼어들지 않는다. */
    fun dayOf(day: LocalDate): PostingDay {
        val rows =
            jdbcClient
                .sql("select companion_id, status, attempts from companion_post where day = :day")
                .param("day", Date.valueOf(day))
                .query { rs, _ -> Triple(rs.getInt("companion_id"), rs.getString("status"), rs.getInt("attempts")) }
                .list()
        return PostingDay(
            posted = rows.count { it.second == POSTED },
            requests = rows.sumOf { it.third },
            companionIds = rows.map { it.first }.toSet(),
        )
    }

    /** 쓰는 중인 기록이 있는가. 있으면 새 기록을 만들지 않는다. */
    fun hasWriting(): Boolean =
        jdbcClient
            .sql("select exists (select 1 from companion_post where status = 'WRITING')")
            .query(Boolean::class.java)
            .single()

    /** 가장 최근 기록 [limit]개의 소재(상태와 상관없이). 같은 소재를 곧 다시 쓰지 않게 한다. */
    fun recentTopics(limit: Int): List<String> =
        jdbcClient
            .sql("select topic from companion_post order by id desc limit :limit")
            .param("limit", limit)
            .query { rs, _ -> rs.getString("topic") }
            .list()

    /** 친구마다 가장 최근에 올린 글의 기록 번호. 올린 적이 없는 친구는 결과에 없다. 번호로 견줘 시계에 기대지 않는다. */
    fun lastPostedByCompanion(): Map<Int, Long> =
        jdbcClient
            .sql(
                """
                select companion_id, max(id) as last_id from companion_post
                where status = 'POSTED' group by companion_id
                """.trimIndent(),
            ).query { rs, _ -> rs.getInt("companion_id") to rs.getLong("last_id") }
            .list()
            .toMap()

    private companion object {
        const val POSTED = "POSTED"
    }
}
