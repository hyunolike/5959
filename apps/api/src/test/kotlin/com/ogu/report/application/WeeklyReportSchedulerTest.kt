package com.ogu.report.application

import com.ogu.shared.schedule.DueTimers
import com.ogu.shared.schedule.ManualScheduler
import com.ogu.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.time.Duration
import java.time.Instant

class WeeklyReportSchedulerTest {
    private val now = Instant.parse("2026-11-12T00:00:00Z")
    private val clock = MutableClock(now)
    private val nextMonday = Instant.parse("2026-11-15T20:00:00Z")
    private val job =
        mock(WeeklyReportJob::class.java).also { given(it.nextRunAt(null)).willReturn(nextMonday) }

    private fun scheduler(idleRecheck: String) =
        WeeklyReportScheduler(
            job,
            clock,
            Duration.ofMinutes(1),
            DueTimers(clock, ManualScheduler(clock)) {},
            idleRecheck,
        )

    @Test
    fun `다 훑은 주는 다음 주 월요일까지 다시 보지 않는다`() {
        assertThat(scheduler("").nextDue()).isEqualTo(nextMonday)
    }

    @Test
    fun `idle-recheck를 주면 그 간격마다 다시 확인한다`() {
        assertThat(scheduler("5s").nextDue()).isEqualTo(now.plusSeconds(5))
    }
}
