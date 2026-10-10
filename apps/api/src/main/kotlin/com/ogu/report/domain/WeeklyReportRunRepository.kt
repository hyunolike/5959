package com.ogu.report.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/** 어느 주의 리포트 만들기가 끝났는지(`weekly_report_run`). 행이 있으면 그 주는 다시 훑지 않는다. */
@Repository
class WeeklyReportRunRepository(
    private val jdbcClient: JdbcClient,
) {
    fun isCompleted(weekStart: LocalDate): Boolean =
        jdbcClient
            .sql("select exists (select 1 from weekly_report_run where week_start = :weekStart)")
            .param("weekStart", Date.valueOf(weekStart))
            .query(Boolean::class.java)
            .single()

    /** 끝났다고 적는다. 다른 인스턴스가 먼저 적었으면 아무것도 하지 않는다. */
    fun complete(
        weekStart: LocalDate,
        now: Instant,
    ) {
        jdbcClient
            .sql(
                """
                insert into weekly_report_run (week_start, completed_at) values (:weekStart, :now)
                on conflict (week_start) do nothing
                """.trimIndent(),
            ).param("weekStart", Date.valueOf(weekStart))
            .param("now", Timestamp.from(now))
            .update()
    }
}
