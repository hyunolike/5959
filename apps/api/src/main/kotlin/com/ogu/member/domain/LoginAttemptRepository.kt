package com.ogu.member.domain

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * `login_attempt` 접근. 모든 쓰기가 한 문장이라 트랜잭션 없이 자동 커밋으로 실행한다. 같은 키로 동시에 들어온 문장은
 * `ON CONFLICT`의 행 잠금이 직렬화한다.
 */
@Repository
class LoginAttemptRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 시도 하나를 센다(비밀번호 검증 전에 부르는 예약). 한 문장으로 다음을 처리하고 갱신 뒤 상태를 돌려준다.
     * - 행이 없으면 1로 만든다.
     * - 아직 막혀 있으면(`blocked_until > now`) 아무것도 바꾸지 않는다. 막힌 동안의 시도가 차단을 늘리지 않게 한다.
     * - 창이 지났으면(`window_started_at <= now - 창`) 창을 지금으로 옮기고 1부터 센다. 지난 차단도 지운다.
     * - 그 밖에는 1을 더한다.
     * - 새 수가 [failureLimit]을 넘으면 `blocked_until = blockedUntil`(지금 + 차단 길이).
     *
     * `DO UPDATE`의 식은 모두 갱신 전 행 값을 본다. 그래서 조건을 식마다 다시 쓴다.
     */
    fun reserve(
        scopeKey: String,
        now: Instant,
        windowExpiredBefore: Instant,
        failureLimit: Int,
        blockedUntil: Instant,
    ): LoginAttempt =
        requireNotNull(
            jdbc.queryForObject(
                RESERVE_SQL,
                MapSqlParameterSource()
                    .addValue("scopeKey", scopeKey)
                    .addValue("now", now.toOffset())
                    .addValue("windowExpiredBefore", windowExpiredBefore.toOffset())
                    .addValue("failureLimit", failureLimit)
                    .addValue("blockedUntil", blockedUntil.toOffset()),
            ) { rs, _ -> rs.toAttempt() },
        )

    fun find(scopeKey: String): LoginAttempt? =
        jdbc
            .query(
                "SELECT failure_count, blocked_until FROM login_attempt WHERE scope_key = :scopeKey",
                MapSqlParameterSource("scopeKey", scopeKey),
            ) { rs, _ -> rs.toAttempt() }
            .firstOrNull()

    fun delete(scopeKey: String) {
        jdbc.update(
            "DELETE FROM login_attempt WHERE scope_key = :scopeKey",
            MapSqlParameterSource("scopeKey", scopeKey),
        )
    }

    /** 창이 아직 지나지 않았을 때만 수를 1 줄인다(0 아래로는 내려가지 않는다). */
    fun decrementIfWindowCurrent(
        scopeKey: String,
        windowExpiredBefore: Instant,
    ) {
        jdbc.update(
            """
            UPDATE login_attempt SET failure_count = GREATEST(failure_count - 1, 0)
            WHERE scope_key = :scopeKey AND window_started_at > :windowExpiredBefore
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("scopeKey", scopeKey)
                .addValue("windowExpiredBefore", windowExpiredBefore.toOffset()),
        )
    }

    /** 창 시작과 차단 끝이 모두 [threshold] 전인 행을 지운다. `GREATEST`는 NULL을 건너뛴다. */
    fun deleteUntouchedBefore(threshold: Instant): Int =
        jdbc.update(
            "DELETE FROM login_attempt WHERE GREATEST(window_started_at, blocked_until) < :threshold",
            MapSqlParameterSource("threshold", threshold.toOffset()),
        )

    private fun Instant.toOffset(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun ResultSet.toAttempt() =
        LoginAttempt(
            failureCount = getInt("failure_count"),
            blockedUntil = getObject("blocked_until", OffsetDateTime::class.java)?.toInstant(),
        )

    private companion object {
        const val RESERVE_SQL = """
            INSERT INTO login_attempt AS a (scope_key, window_started_at, failure_count, blocked_until)
            VALUES (:scopeKey, :now, 1, CASE WHEN 1 > :failureLimit THEN CAST(:blockedUntil AS timestamptz) END)
            ON CONFLICT (scope_key) DO UPDATE SET
              window_started_at =
                CASE WHEN a.blocked_until > :now THEN a.window_started_at
                     WHEN a.window_started_at <= :windowExpiredBefore THEN EXCLUDED.window_started_at
                     ELSE a.window_started_at END,
              failure_count =
                CASE WHEN a.blocked_until > :now THEN a.failure_count
                     WHEN a.window_started_at <= :windowExpiredBefore THEN 1
                     ELSE a.failure_count + 1 END,
              blocked_until =
                CASE WHEN a.blocked_until > :now THEN a.blocked_until
                     WHEN (CASE WHEN a.window_started_at <= :windowExpiredBefore THEN 1
                                ELSE a.failure_count + 1 END) > :failureLimit
                       THEN CAST(:blockedUntil AS timestamptz)
                     ELSE NULL END
            RETURNING failure_count, blocked_until
        """
    }
}
