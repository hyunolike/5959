package com.ogu.notification.stream

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** [RealtimeConnectionState]를 5초마다 확인한다(research R5). */
@Component
class RealtimeConnectionProbe(
    private val state: RealtimeConnectionState,
) {
    @Scheduled(initialDelayString = PROBE_INTERVAL, fixedDelayString = PROBE_INTERVAL)
    fun probe() = state.probe()

    companion object {
        /** 기본 5초. 테스트는 `ogu.notification.probe-interval`로 줄인다. */
        const val PROBE_INTERVAL = "\${ogu.notification.probe-interval:5s}"
    }
}
