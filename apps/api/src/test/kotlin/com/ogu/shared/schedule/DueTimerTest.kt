package com.ogu.shared.schedule

import com.ogu.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

class DueTimerTest {
    private val start = Instant.parse("2026-11-09T00:00:00Z")
    private val clock = MutableClock(start)
    private val scheduler = ManualScheduler(clock)

    @Test
    fun `US4-AC4 기동 때 DB에서 읽은 가장 이른 시각에 돈다`() {
        val job = RecordingJob(next = start.plusSeconds(30))
        val timers = DueTimers(clock, scheduler) {}
        timers.register(job)

        timers.onReady()
        scheduler.advance(Duration.ofSeconds(29))
        assertThat(job.runs).isZero()

        scheduler.advance(Duration.ofSeconds(1))
        assertThat(job.runs).isEqualTo(1)
    }

    @Test
    fun `다음 시각이 계속 지난 시각이면 1초에 한 번만 돈다`() {
        // 일이 실패해 next_attempt_at이 그대로 남은 경우. 간격이 없으면 쉬지 않고 DB를 두드린다.
        val job = RecordingJob(next = start, failRun = true)
        val timer = timerFor(job)
        timer.rearm()

        scheduler.advance(Duration.ZERO)
        assertThat(job.runs).isEqualTo(1)
        assertThat(timer.nextAt).isEqualTo(start.plusSeconds(1))

        repeat(3) { scheduler.advance(Duration.ofSeconds(1)) }
        assertThat(job.runs).isEqualTo(4)
    }

    @Test
    fun `더 이른 시각으로만 다시 건다`() {
        val timer = timerFor(RecordingJob(next = null))
        timer.arm(start.plusSeconds(60))

        timer.arm(start.plusSeconds(120))
        assertThat(timer.nextAt).isEqualTo(start.plusSeconds(60))

        timer.arm(start.plusSeconds(10))
        assertThat(timer.nextAt).isEqualTo(start.plusSeconds(10))
        assertThat(scheduler.pending).isEqualTo(1)
    }

    @Test
    fun `지난 시각이면 바로 돈다`() {
        val job = RecordingJob(next = null)
        val timer = timerFor(job)

        timer.arm(start.minusSeconds(300))
        scheduler.advance(Duration.ZERO)

        assertThat(job.runs).isEqualTo(1)
    }

    @Test
    fun `돌고 나면 다음 시각을 다시 읽는다`() {
        val job = RecordingJob(next = start.plusSeconds(10))
        val timer = timerFor(job)
        timer.rearm()

        job.next = start.plusSeconds(40)
        scheduler.advance(Duration.ofSeconds(10))
        assertThat(job.runs).isEqualTo(1)
        assertThat(timer.nextAt).isEqualTo(start.plusSeconds(40))

        job.next = null
        scheduler.advance(Duration.ofSeconds(30))
        assertThat(job.runs).isEqualTo(2)
        assertThat(timer.nextAt).isNull()
        assertThat(scheduler.pending).isZero()
    }

    @Test
    fun `할 일이 없으면 걸지 않는다`() {
        val timer = timerFor(RecordingJob(next = null))

        timer.rearm()

        assertThat(timer.nextAt).isNull()
        assertThat(scheduler.pending).isZero()
    }

    @Test
    fun `도는 동안 들어온 시각을 잃지 않는다`() {
        lateinit var timer: DueTimer
        val job =
            RecordingJob(next = null) {
                timer.arm(clock.instant().plusSeconds(5))
            }
        timer = timerFor(job)

        timer.arm(start)
        scheduler.advance(Duration.ZERO)

        assertThat(timer.nextAt).isEqualTo(start.plusSeconds(5))
    }

    @Test
    fun `다음 시각을 읽지 못하면 5분 뒤에 다시 읽는다`() {
        val job = RecordingJob(next = null, failNext = true)
        val timer = timerFor(job)

        timer.rearm()

        assertThat(timer.nextAt).isEqualTo(start.plus(Duration.ofMinutes(5)))
    }

    @Test
    fun `작업이 실패해도 다음 시각을 건다`() {
        val job = RecordingJob(next = start, failRun = true)
        val timer = timerFor(job)
        timer.rearm()

        job.next = start.plusSeconds(30)
        scheduler.advance(Duration.ZERO)

        assertThat(timer.nextAt).isEqualTo(start.plusSeconds(30))
    }

    @Test
    fun `커밋 전에는 걸지 않고 커밋 뒤에 건다`() {
        val timer = timerFor(RecordingJob(next = null))
        TransactionSynchronizationManager.initSynchronization()
        try {
            timer.armAfterCommit(start.plusSeconds(10))
            assertThat(timer.nextAt).isNull()

            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            assertThat(timer.nextAt).isEqualTo(start.plusSeconds(10))
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    private fun timerFor(job: DueJob): DueTimer = DueTimers(clock, scheduler) {}.register(job)

    private class RecordingJob(
        var next: Instant?,
        val failNext: Boolean = false,
        val failRun: Boolean = false,
        val onRun: () -> Unit = {},
    ) : DueJob {
        var runs = 0
        override val name = "test"

        override fun nextDue(): Instant? {
            check(!failNext) { "DB에 닿지 못함" }
            return next
        }

        override fun runDue() {
            runs++
            onRun()
            check(!failRun) { "실패" }
        }
    }
}

/** 시계를 움직일 때 차례가 된 일을 같은 스레드에서 부르는 스케줄러. */
class ManualScheduler(
    private val clock: MutableClock,
) : DelayScheduler {
    private val tasks = mutableListOf<Pair<Instant, Runnable>>()

    val pending: Int
        get() = tasks.size

    override fun schedule(
        delay: Duration,
        task: Runnable,
    ): Cancellable {
        val entry = clock.instant().plus(delay) to task
        tasks += entry
        return Cancellable { tasks.remove(entry) }
    }

    fun advance(duration: Duration) {
        clock.advance(duration)
        while (true) {
            val due = tasks.filter { !it.first.isAfter(clock.instant()) }.minByOrNull { it.first } ?: return
            tasks.remove(due)
            due.second.run()
        }
    }
}
