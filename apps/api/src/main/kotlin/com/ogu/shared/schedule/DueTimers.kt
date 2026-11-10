package com.ogu.shared.schedule

import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 모든 [DueTimer]를 만들고, 기동 때와 매일 정리 때 한꺼번에 다시 계산한다(018 research R3).
 *
 * 스레드 둘을 함께 쓴다(지금까지의 `@Scheduled` 풀과 같은 크기). `TaskScheduler` 빈으로 두지 않는다.
 * 그런 빈이 있으면 Boot가 `@Scheduled`용 기본 스케줄러를 만들지 않는다(`StreamTimer`와 같은 까닭).
 */
@Component
class DueTimers internal constructor(
    private val clock: Clock,
    private val scheduler: DelayScheduler,
    private val shutdown: () -> Unit,
) : DisposableBean {
    private val timers = CopyOnWriteArrayList<DueTimer>()

    // 생성자가 여럿이라 Spring이 쓸 것을 정해 준다. 나머지는 테스트가 시간을 직접 움직이려고 둔 것이다.
    @Autowired
    constructor(clock: Clock) : this(clock, threadScheduler())

    private constructor(clock: Clock, pool: ThreadPoolTaskScheduler) : this(
        clock,
        DelayScheduler { delay, task ->
            val future = pool.schedule(task, pool.clock.instant().plus(delay))
            Cancellable { future.cancel(false) }
        },
        { pool.shutdown() },
    )

    /** [job]의 타이머를 만든다. 걸리는 것은 기동이 끝난 뒤([ApplicationReadyEvent])부터다. */
    fun register(job: DueJob): DueTimer = DueTimer(job, clock, scheduler, RETRY_ON_ERROR, MIN_GAP).also(timers::add)

    /** 모든 타이머의 다음 시각을 DB에서 다시 읽는다. */
    fun rearmAll() = timers.forEach(DueTimer::rearm)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() = rearmAll()

    override fun destroy() {
        timers.forEach(DueTimer::cancel)
        shutdown()
    }

    companion object {
        private val RETRY_ON_ERROR: Duration = Duration.ofMinutes(5)
        private val MIN_GAP: Duration = Duration.ofSeconds(1)

        private fun threadScheduler() =
            ThreadPoolTaskScheduler().apply {
                poolSize = 2
                setThreadNamePrefix("due-timer-")
                setWaitForTasksToCompleteOnShutdown(false)
                initialize()
            }
    }
}
