package com.ogu.notification.stream

import org.springframework.beans.factory.DisposableBean
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ScheduledFuture

/**
 * 하트비트와 안전망의 시각을 맞추는 타이머 스레드 하나. 할 일은 [SseHub]의 실행기에 넘기기만 한다.
 *
 * `TaskScheduler` 빈으로 두지 않는다. 그런 빈이 있으면 Spring Boot가 `@Scheduled`용 기본 스케줄러(스레드 2개)를 만들지 않고
 * 이것을 대신 써서, 감정 분석 재시도와 정리 작업이 이 스레드 하나에 몰린다.
 */
@Component
class StreamTimer : DisposableBean {
    private val scheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("notification-stream-timer-")
            setWaitForTasksToCompleteOnShutdown(false)
            initialize()
        }

    /** [delay]가 지난 뒤부터 [delay]마다 [task]를 부른다. */
    fun every(
        delay: Duration,
        task: Runnable,
    ): ScheduledFuture<*> = scheduler.scheduleWithFixedDelay(task, scheduler.clock.instant().plus(delay), delay)

    override fun destroy() {
        scheduler.shutdown()
    }
}
