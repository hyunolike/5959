package com.ogu.notification.stream

import com.ogu.notification.application.NotificationProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.SmartLifecycle
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture

/**
 * 안전망 따라잡기(research R5). pub/sub는 많아야 한 번 전달하므로, 커밋과 발행 사이에 프로세스가 죽거나 구독이 잠깐 끊겨
 * 놓친 신호를 주기마다 메운다. 주기마다 회원별 마지막 번호를 한 번에 읽어(쓰기 실행기에서) 뒤처진 연결만 따라잡는다.
 *
 * 주기는 평소 [NotificationProperties.safetyDrainInterval](60초)이고, Redis가 내려가 있으면
 * ([RealtimeConnectionChanged] DOWN) [NotificationProperties.outageDrainInterval](5초)로 줄인다. 돌아오면(UP) 모든 연결을
 * 한 번 따라잡은 뒤 평소 주기로 되돌린다.
 */
@Component
class SafetyDrain(
    private val hub: SseHub,
    private val state: RealtimeConnectionState,
    private val properties: NotificationProperties,
    private val timer: StreamTimer,
    @param:Qualifier(StreamConfig.STREAM_EXECUTOR) private val executor: Executor,
) : SmartLifecycle {
    private var future: ScheduledFuture<*>? = null

    @Volatile
    private var interval: Duration = properties.safetyDrainInterval

    @Volatile
    private var running = false

    override fun start() {
        running = true
        reschedule(intervalFor(state.current))
    }

    override fun stop() {
        running = false
        synchronized(this) {
            future?.cancel(false)
            future = null
        }
    }

    override fun isRunning(): Boolean = running

    /** 지금 쓰는 주기. */
    fun currentInterval(): Duration = interval

    @EventListener
    fun on(event: RealtimeConnectionChanged) {
        if (event.state == RealtimeConnection.UP) hub.drainAll()
        if (running) reschedule(intervalFor(event.state))
    }

    /** 타이머 스레드는 시각만 맞춘다. DB를 읽는 일은 쓰기 실행기에 넘겨, DB가 느려도 하트비트가 밀리지 않게 한다. */
    private fun tick() {
        try {
            executor.execute(::drainLagging)
        } catch (e: RejectedExecutionException) {
            log.warn("안전망 따라잡기를 올리지 못했습니다(대기열 가득 참): {}", e.message)
        }
    }

    @Suppress("TooGenericExceptionCaught") // DB가 잠깐 안 되더라도 다음 주기에 다시 한다
    private fun drainLagging() {
        try {
            hub.drainLagging()
        } catch (e: RuntimeException) {
            log.warn("안전망 따라잡기에 실패했습니다: {}", e.message)
        }
    }

    private fun intervalFor(connection: RealtimeConnection): Duration =
        if (connection == RealtimeConnection.DOWN) properties.outageDrainInterval else properties.safetyDrainInterval

    private fun reschedule(interval: Duration) {
        synchronized(this) {
            future?.cancel(false)
            this.interval = interval
            future = timer.every(interval, ::tick)
        }
        log.info("알림 안전망 주기를 {}초로 둡니다", interval.toMillis() / MILLIS_PER_SECOND)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        val log = LoggerFactory.getLogger(SafetyDrain::class.java)
    }
}
