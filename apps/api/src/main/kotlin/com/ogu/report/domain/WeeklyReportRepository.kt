package com.ogu.report.domain

import com.ogu.emotion.EmotionType
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/** 편지의 상태(008 research R6). DB 체크 제약 `weekly_report_letter_status_check`와 같은 목록이다. */
enum class LetterStatus {
    /** 쓰는 중이거나 다시 시도를 기다린다. */
    PENDING,
    DONE,

    /** 24시간 동안 쓰지 못해 편지 없이 닫았다. */
    GIVEN_UP,

    /** 위기 글이 있던 주라 AI에게 맡기지 않았다. 화면이 정해 둔 문구와 도움받을 곳을 보인다. */
    SUPPORT,
}

/** 발행할 때 한 번 센 수치(008 research R5). */
data class WeeklyStats(
    val postCount: Int,
    /** 모든 감정이 키로 있다. */
    val emotionCounts: Map<EmotionType, Int>,
    val unanalyzedCount: Int,
    val topEmotion: EmotionType?,
    val defeatedCount: Int,
    val receivedLikes: Int,
    val receivedComments: Int,
)

data class WeeklyReport(
    val id: Long,
    val memberId: Long,
    val weekStart: LocalDate,
    val stats: WeeklyStats,
    val letterStatus: LetterStatus,
    val letter: String?,
    val letterAttempts: Int,
    val publishedAt: Instant,
)

/**
 * `weekly_report` 저장소. 넣기는 `(member_id, week_start)` 유일 제약에 기대어 한 번만 된다. 편지의 재시도는
 * `FOR UPDATE SKIP LOCKED`로 맡아 여러 인스턴스가 같은 리포트를 두 번 쓰지 않는다.
 */
@Repository
class WeeklyReportRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 리포트를 넣고 ID를 돌려준다. 그 회원의 그 주 리포트가 이미 있으면 아무것도 하지 않고 null이다. 그때는 이벤트도
     * 내지 않는다(research R4). [crisisWeek]이면 편지를 AI에게 맡기지 않는다.
     */
    fun insertIfAbsent(
        memberId: Long,
        weekStart: LocalDate,
        stats: WeeklyStats,
        crisisWeek: Boolean,
        now: Instant,
    ): Long? =
        jdbcClient
            .sql(
                """
                insert into weekly_report (member_id, week_start, post_count, emotion_counts, unanalyzed_count,
                                           top_emotion, defeated_count, received_likes, received_comments,
                                           letter_status, letter_next_attempt_at, published_at)
                values (:memberId, :weekStart, :postCount, cast(:emotionCounts as jsonb), :unanalyzedCount,
                        :topEmotion, :defeatedCount, :receivedLikes, :receivedComments,
                        :letterStatus, :nextAttemptAt, :now)
                on conflict on constraint weekly_report_member_week_key do nothing
                returning id
                """.trimIndent(),
            ).param("memberId", memberId)
            .param("weekStart", Date.valueOf(weekStart))
            .param("postCount", stats.postCount)
            .param("emotionCounts", toJson(stats.emotionCounts))
            .param("unanalyzedCount", stats.unanalyzedCount)
            .param("topEmotion", stats.topEmotion?.name)
            .param("defeatedCount", stats.defeatedCount)
            .param("receivedLikes", stats.receivedLikes)
            .param("receivedComments", stats.receivedComments)
            .param("letterStatus", (if (crisisWeek) LetterStatus.SUPPORT else LetterStatus.PENDING).name)
            .param("nextAttemptAt", if (crisisWeek) null else Timestamp.from(now))
            .param("now", Timestamp.from(now))
            .query(Long::class.java)
            .optional()
            .orElse(null)

    fun find(
        memberId: Long,
        weekStart: LocalDate,
    ): WeeklyReport? =
        jdbcClient
            .sql("select * from weekly_report where member_id = :memberId and week_start = :weekStart")
            .param("memberId", memberId)
            .param("weekStart", Date.valueOf(weekStart))
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** 회원의 리포트를 최신 주부터. [before]가 있으면 그보다 앞선 주만 본다. */
    fun findPage(
        memberId: Long,
        before: LocalDate?,
        size: Int,
    ): List<WeeklyReport> {
        val keyset = if (before == null) "" else "and week_start < :before"
        return jdbcClient
            .sql(
                """
                select * from weekly_report
                where member_id = :memberId $keyset
                order by week_start desc
                limit :size
                """.trimIndent(),
            ).param("memberId", memberId)
            .apply { if (before != null) param("before", Date.valueOf(before)) }
            .param("size", size)
            .query(rowMapper)
            .list()
    }

    /** [memberIds] 가운데 [weekStart] 주의 리포트가 이미 있는 회원. */
    fun memberIdsWithReport(
        weekStart: LocalDate,
        memberIds: Collection<Long>,
    ): Set<Long> {
        if (memberIds.isEmpty()) return emptySet()
        return jdbcClient
            .sql("select member_id from weekly_report where week_start = :weekStart and member_id in (:memberIds)")
            .param("weekStart", Date.valueOf(weekStart))
            .param("memberIds", memberIds.toSet())
            .query { rs, _ -> rs.getLong("member_id") }
            .list()
            .toSet()
    }

    /** 보관 기간이 지난 리포트를 [limit]개까지 지운다. 지운 수를 돌려준다. */
    fun deletePublishedBefore(
        cutoff: Instant,
        limit: Int,
    ): Int =
        jdbcClient
            .sql(
                """
                delete from weekly_report where id in (
                  select id from weekly_report where published_at < :cutoff
                  order by published_at limit :limit for update skip locked)
                """.trimIndent(),
            ).param("cutoff", Timestamp.from(cutoff))
            .param("limit", limit)
            .update()

    private val rowMapper =
        RowMapper { rs, _ ->
            val counts = fromJson(rs.getString("emotion_counts"))
            WeeklyReport(
                id = rs.getLong("id"),
                memberId = rs.getLong("member_id"),
                weekStart = rs.getDate("week_start").toLocalDate(),
                stats =
                    WeeklyStats(
                        postCount = rs.getInt("post_count"),
                        emotionCounts = EmotionType.entries.associateWith { counts[it.name] ?: 0 },
                        unanalyzedCount = rs.getInt("unanalyzed_count"),
                        topEmotion = rs.getString("top_emotion")?.let(EmotionType::valueOf),
                        defeatedCount = rs.getInt("defeated_count"),
                        receivedLikes = rs.getInt("received_likes"),
                        receivedComments = rs.getInt("received_comments"),
                    ),
                letterStatus = LetterStatus.valueOf(rs.getString("letter_status")),
                letter = rs.getString("letter"),
                letterAttempts = rs.getInt("letter_attempts"),
                publishedAt = rs.getTimestamp("published_at").toInstant(),
            )
        }

    internal fun mapper(): RowMapper<WeeklyReport> = rowMapper

    private companion object {
        val ENTRY = Regex("\"([A-Z_]+)\"\\s*:\\s*(\\d+)")

        /** 감정 이름과 정수뿐이라 JSON 라이브러리 없이 쓴다. */
        fun toJson(counts: Map<EmotionType, Int>): String =
            EmotionType.entries.joinToString(",", "{", "}") { "\"${it.name}\":${counts[it] ?: 0}" }

        fun fromJson(json: String): Map<String, Int> =
            ENTRY.findAll(json).associate { entry ->
                entry.groupValues[1] to entry.groupValues[2].toInt()
            }
    }
}
