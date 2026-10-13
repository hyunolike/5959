package com.ogu.companion.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Date
import java.time.LocalDate

/** `companion_daily_usage` 저장소(009 data-model.md). 하루(한국 시간)에 AI에 댓글을 청한 횟수다. */
@Repository
class DailyUsageRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * [day]의 몫 하나를 받는다. 쓴 횟수가 [limit]보다 작을 때만 1 올리는 한 문장이라, 여러 인스턴스가 함께 받아도 넘지
     * 않는다. 받지 못하면 false다.
     */
    fun reserve(
        day: LocalDate,
        limit: Int,
    ): Boolean {
        if (limit <= 0) return false
        return jdbcClient
            .sql(
                """
                insert into companion_daily_usage (day, used) values (:day, 1)
                on conflict (day) do update set used = companion_daily_usage.used + 1
                  where companion_daily_usage.used < :limit
                returning used
                """.trimIndent(),
            ).param("day", Date.valueOf(day))
            .param("limit", limit)
            .query(Int::class.java)
            .optional()
            .isPresent
    }

    /** 받은 몫을 돌려준다. 0 아래로는 내려가지 않는다. */
    fun release(day: LocalDate) {
        jdbcClient
            .sql("update companion_daily_usage set used = used - 1 where day = :day and used > 0")
            .param("day", Date.valueOf(day))
            .update()
    }
}
