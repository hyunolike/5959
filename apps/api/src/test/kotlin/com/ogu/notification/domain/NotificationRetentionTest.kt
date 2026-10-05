package com.ogu.notification.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class NotificationRetentionTest {
    private val now = Instant.parse("2026-10-05T03:00:00Z")
    private val retention = NotificationRetention(Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(90))

    @Test
    fun `만든 지 89일 23시간 59분 59초인 알림은 보관 기간 안이다`() {
        val createdAt = now.minus(Duration.ofDays(89)).minus(Duration.ofHours(23)).minusSeconds(59 * 60 + 59)

        assertThat(retention.isRetained(createdAt)).isTrue()
    }

    @Test
    fun `만든 지 정확히 90일인 알림은 보관 기간 밖이다`() {
        assertThat(retention.isRetained(now.minus(Duration.ofDays(90)))).isFalse()
        assertThat(retention.isRetained(now.minus(Duration.ofDays(90)).minusNanos(1_000))).isFalse()
    }

    @Test
    fun `기준 시각은 주입한 시계에서 보관 기간을 뺀 값이다`() {
        assertThat(retention.cutoff()).isEqualTo(Instant.parse("2026-07-07T03:00:00Z"))
    }

    @Test
    fun `SQL 조건은 기준 시각보다 늦게 만든 알림만 남긴다`() {
        assertThat(NotificationRetention.CONDITION).isEqualTo("created_at > :retentionCutoff")
        assertThat(retention.params()).containsEntry("retentionCutoff", Timestamp.from(retention.cutoff()))
    }
}
