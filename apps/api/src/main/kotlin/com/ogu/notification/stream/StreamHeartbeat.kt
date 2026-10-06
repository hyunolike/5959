package com.ogu.notification.stream

import com.ogu.notification.application.NotificationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.ScheduledFuture

/**
 * [NotificationProperties.heartbeat](25초)마다 모든 연결에 `: hb` 주석 줄과 `ping` 이벤트를 보낸다(research R2). 중간 장비가
 * 조용한 연결을 끊지 않게 하고, 끊긴 연결은 쓰기 실패로 늦어도 한 주기 안에 허브에서 지운다. 웹은 `ping`이 60초 동안
 * 오지 않으면 연결이 죽었다고 보고 다시 붙는다.
 */
@Component
class StreamHeartbeat(
    private val hub: SseHub,
    private val properties: NotificationProperties,
    private val timer: StreamTimer,
) : SmartLifecycle {
    @Volatile
    private var future: ScheduledFuture<*>? = null

    override fun start() {
        future = timer.every(properties.heartbeat, hub::heartbeatAll)
    }

    override fun stop() {
        future?.cancel(false)
        future = null
    }

    override fun isRunning(): Boolean = future != null
}
