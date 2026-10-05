package com.ogu.notification.domain

import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 알림 보관 기간(FR-010, research R10). 만든 뒤 [period] 동안만 보관 기간 안이다. 목록, 안 읽은 수, 재전송, 읽음 처리는
 * 모두 이 조건([CONDITION], [params])만 쓴다. 정리 작업이 늦어도 화면에서는 보관 기간이 정확히 지켜진다.
 *
 * 경계: 만든 지 89일 23:59:59는 안이고, 정확히 90일이 된 순간부터 밖이다(`created_at > now − 90일`).
 */
class NotificationRetention(
    private val clock: Clock,
    private val period: Duration,
) {
    /** 이 시각보다 늦게 만든 알림만 보관 기간 안이다. */
    fun cutoff(): Instant = clock.instant().minus(period)

    fun isRetained(createdAt: Instant): Boolean = createdAt.isAfter(cutoff())

    /** [CONDITION]의 바인딩 값. */
    fun params(): Map<String, Any> = mapOf(PARAM to Timestamp.from(cutoff()))

    companion object {
        private const val PARAM = "retentionCutoff"

        /** `notification` 테이블에 붙이는 SQL 조건. [params]를 함께 넘긴다. */
        const val CONDITION = "created_at > :$PARAM"
    }
}
