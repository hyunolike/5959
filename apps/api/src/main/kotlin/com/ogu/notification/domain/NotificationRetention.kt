package com.ogu.notification.domain

import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 알림 보관 기간(FR-010, research R10). 만든 뒤 [period] 동안만 보관 기간 안이다. 목록, 안 읽은 수, 재전송, 읽음 처리는
 * 모두 이 조건([condition], [params])만 쓴다. 정리 작업이 늦어도 화면에서는 보관 기간이 정확히 지켜진다.
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

    /** [condition]의 바인딩 값. */
    fun params(): Map<String, Any> = mapOf(PARAM to Timestamp.from(cutoff()))

    companion object {
        private const val PARAM = "retentionCutoff"
        private val ALIAS = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /**
         * 별칭 [alias]로 가리킨 `notification` 행에 붙이는 SQL 조건. 조인해도 `created_at`이 모호하지 않게 별칭을 꼭 받는다.
         * [params]를 함께 넘긴다.
         */
        fun condition(alias: String): String {
            require(ALIAS.matches(alias)) { "SQL 별칭이 아닙니다: $alias" }
            return "$alias.created_at > :$PARAM"
        }
    }
}
