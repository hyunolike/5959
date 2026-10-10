package com.ogu.report.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/** 편지의 처리 일정을 다루는 쿼리(008 research R6). 같은 테이블(`weekly_report`)의 편지 열만 고친다. */
@Repository
class WeeklyLetterRepository(
    private val jdbcClient: JdbcClient,
    private val reports: WeeklyReportRepository,
) {
    /**
     * 편지를 쓸 차례인 리포트 하나를 잠근다. [memberId]와 [weekStart]를 주면 그 리포트만 본다. 다른 실행기가 잡고 있으면
     * 건너뛴다. 나중에 발행된 것부터 잡아, 밀린 것이 새것을 막지 않게 한다.
     */
    fun lockDue(
        now: Instant,
        memberId: Long? = null,
        weekStart: LocalDate? = null,
    ): WeeklyReport? {
        val one = memberId != null && weekStart != null
        val only = if (one) "and member_id = :memberId and week_start = :weekStart" else ""
        var spec =
            jdbcClient
                .sql(
                    """
                    select * from weekly_report
                    where letter_status = 'PENDING' and letter_next_attempt_at <= :now $only
                    order by published_at desc
                    limit 1
                    for update skip locked
                    """.trimIndent(),
                ).param("now", Timestamp.from(now))
        if (memberId != null && weekStart != null) {
            spec = spec.param("memberId", memberId).param("weekStart", Date.valueOf(weekStart))
        }
        return spec.query(reports.mapper()).optional().orElse(null)
    }

    /** 시도를 맡았다고 적는다. 다음 차례를 미뤄 두어, 호출이 끝나기 전에 다른 실행기가 같은 리포트를 잡지 않는다. */
    fun markAttempt(
        reportId: Long,
        nextAttemptAt: Instant,
    ) {
        jdbcClient
            .sql(
                """
                update weekly_report set letter_attempts = letter_attempts + 1, letter_next_attempt_at = :next
                where id = :id
                """.trimIndent(),
            ).param("next", Timestamp.from(nextAttemptAt))
            .param("id", reportId)
            .update()
    }

    /** 편지를 적는다. 아직 기다리는 중일 때만 적는다(그 사이 그만뒀으면 버린다). 적었으면 true다. */
    fun complete(
        reportId: Long,
        letter: String,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update weekly_report
                set letter_status = 'DONE', letter = :letter, letter_next_attempt_at = null, letter_last_error = null
                where id = :id and letter_status = 'PENDING'
                """.trimIndent(),
            ).param("letter", letter)
            .param("id", reportId)
            .update() == 1

    fun recordFailure(
        reportId: Long,
        errorKind: String,
    ) {
        jdbcClient
            .sql("update weekly_report set letter_last_error = :kind where id = :id and letter_status = 'PENDING'")
            .param("kind", errorKind)
            .param("id", reportId)
            .update()
    }

    fun giveUp(reportId: Long) {
        jdbcClient
            .sql(
                """
                update weekly_report set letter_status = 'GIVEN_UP', letter_next_attempt_at = null
                where id = :id and letter_status = 'PENDING'
                """.trimIndent(),
            ).param("id", reportId)
            .update()
    }
}
