package com.ogu.notification.stream

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** [RealtimeConnectionState]를 5초마다 확인한다(research R5). */
@Component
class RealtimeConnectionProbe(
    private val state: RealtimeConnectionState,
) {
    @Scheduled(initialDelay = PROBE_INTERVAL_MS, fixedDelay = PROBE_INTERVAL_MS)
    fun probe() = state.probe()

    companion object {
        const val PROBE_INTERVAL_MS = 5_000L
    }
}
