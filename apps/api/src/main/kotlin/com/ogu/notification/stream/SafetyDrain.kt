package com.ogu.notification.stream

import com.ogu.notification.application.NotificationProperties
import com.ogu.shared.schedule.ActivityGate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture

/**
 * 안전망 따라잡기(018 research R4). 신호를 버렸거나(대기열 가득 참) 배포 중 옛 서버에 붙은 연결이 놓친 알림을
 * [NotificationProperties.safetyDrainInterval](60초)마다 메운다. 주기마다 회원별 마지막 번호를 한 번에 읽어(쓰기 실행기에서)
 * 뒤처진 연결만 따라잡는다.
 *
 * 회원이 최근에 쓴 동안([ActivityGate])만 돈다. 회원이 떠나면 쿼리를 내지 않아 DB가 잘 수 있다.
 */
@Component
class SafetyDrain(
    private val hub: SseHub,
    private val activity: ActivityGate,
    private val properties: NotificationProperties,
    private val timer: StreamTimer,
    @param:Qualifier(StreamConfig.STREAM_EXECUTOR) private val executor: Executor,
) : SmartLifecycle {
    @Volatile
    private var future: ScheduledFuture<*>? = null

    override fun start() {
        future = timer.every(properties.safetyDrainInterval, ::tick)
    }

    override fun stop() {
        future?.cancel(false)
        future = null
    }

    override fun isRunning(): Boolean = future != null

    /** 타이머 스레드는 시각만 맞춘다. DB를 읽는 일은 쓰기 실행기에 넘겨, DB가 느려도 하트비트가 밀리지 않게 한다. */
    private fun tick() {
        if (!activity.isActive()) return
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

    private companion object {
        val log = LoggerFactory.getLogger(SafetyDrain::class.java)
    }
}
