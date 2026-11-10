package com.ogu.support

import com.ogu.shared.schedule.Cancellable
import com.ogu.shared.schedule.DelayScheduler
import com.ogu.shared.schedule.DueTimers
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 타이머 경로 테스트(018 T010)의 컨텍스트 설정. 앱의 [DueTimers] 대신, 테스트가 시계를 움직일 때만 울리는 타이머를 쓴다.
 * 이 설정을 쓰는 테스트는 보려는 모듈의 `scheduler-enabled`를 켠 채로 둔다.
 */
@TestConfiguration(proxyBeanMethods = false)
class ManualTimersConfiguration {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS))

    @Bean
    fun manualTimerScheduler(clock: MutableClock): ManualTimerScheduler = ManualTimerScheduler(clock)

    @Bean
    @Primary
    fun manualDueTimers(
        clock: MutableClock,
        scheduler: ManualTimerScheduler,
    ): DueTimers = DueTimers(clock, scheduler) {}
}

/**
 * 시계를 움직일 때 차례가 된 일을 테스트 스레드에서 부르는 스케줄러. 커밋 뒤 리스너가 다른 스레드에서 타이머를 걸므로
 * 목록은 잠금 안에서만 만진다. 일은 잠금 밖에서 부른다(일이 다시 거는 동안 다른 스레드와 서로 기다리지 않게).
 */
class ManualTimerScheduler(
    private val clock: MutableClock,
) : DelayScheduler {
    private val lock = ReentrantLock()
    private val tasks = mutableListOf<Pair<Instant, Runnable>>()

    override fun schedule(
        delay: Duration,
        task: Runnable,
    ): Cancellable {
        val entry = clock.instant().plus(delay) to task
        lock.withLock { tasks += entry }
        return Cancellable { lock.withLock { tasks.remove(entry) } }
    }

    /** 시계를 [duration]만큼 움직이고, 그때까지 차례가 된 일을 이른 것부터 부른다. */
    fun advance(duration: Duration) {
        clock.advance(duration)
        while (true) {
            val due =
                lock.withLock {
                    tasks
                        .filter { !it.first.isAfter(clock.instant()) }
                        .minByOrNull { it.first }
                        ?.also { tasks.remove(it) }
                } ?: return
            due.second.run()
        }
    }
}
