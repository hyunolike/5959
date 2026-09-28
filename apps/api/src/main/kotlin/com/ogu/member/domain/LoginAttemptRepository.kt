package com.ogu.member.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface LoginAttemptRepository : JpaRepository<LoginAttempt, String> {
    /**
     * 실패 한 번을 원자적으로 센다. 행이 없으면 만들고, 있으면 창이 지났는지에 따라 초기화하거나 1을 더한다.
     * 동시에 같은 키로 들어온 실패도 `ON CONFLICT`가 행 잠금으로 직렬화하므로 빠짐없이 센다.
     *
     * - 창이 지났다: `window_started_at <= windowExpiredBefore` (= 현재 시각 - 창 길이)
     * - 한도에 닿으면 `blocked_until = blockedUntil`(= 현재 시각 + 차단 길이). 창을 새로 시작하면 이전 차단을 지운다.
     *
     * `DO UPDATE`의 식은 모두 갱신 전 행 값을 본다. 그래서 새 실패 수를 식마다 다시 계산한다.
     */
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO login_attempt AS a (scope_key, window_started_at, failure_count, blocked_until)
            VALUES (:scopeKey, :now, 1, CASE WHEN 1 >= :failureLimit THEN CAST(:blockedUntil AS timestamptz) END)
            ON CONFLICT (scope_key) DO UPDATE SET
              window_started_at =
                CASE WHEN a.window_started_at <= :windowExpiredBefore THEN EXCLUDED.window_started_at
                     ELSE a.window_started_at END,
              failure_count =
                CASE WHEN a.window_started_at <= :windowExpiredBefore THEN 1
                     ELSE a.failure_count + 1 END,
              blocked_until =
                CASE WHEN (CASE WHEN a.window_started_at <= :windowExpiredBefore THEN 1
                                ELSE a.failure_count + 1 END) >= :failureLimit
                       THEN CAST(:blockedUntil AS timestamptz)
                     WHEN a.window_started_at <= :windowExpiredBefore THEN NULL
                     ELSE a.blocked_until END
        """,
    )
    fun recordFailure(
        @Param("scopeKey") scopeKey: String,
        @Param("now") now: Instant,
        @Param("windowExpiredBefore") windowExpiredBefore: Instant,
        @Param("failureLimit") failureLimit: Int,
        @Param("blockedUntil") blockedUntil: Instant,
    ): Int

    /** 창 시작과 차단 끝이 모두 [threshold] 전인 행을 지운다. `GREATEST`는 NULL을 건너뛴다. */
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = "DELETE FROM login_attempt WHERE GREATEST(window_started_at, blocked_until) < :threshold",
    )
    fun deleteUntouchedBefore(
        @Param("threshold") threshold: Instant,
    ): Int
}
