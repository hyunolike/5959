package com.ogu.notification.stream

import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.connection.RedisConnectionFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 실시간 전달 계층(Redis)의 상태. */
enum class RealtimeConnection {
    UP,
    DOWN,
}

/**
 * Redis 상태가 바뀌었다. 같은 인스턴스 안에서만 쓰는 애플리케이션 이벤트다. DOWN이면 안전망 따라잡기 주기를 줄이고
 * (`ogu.notification.outage-drain-interval`), UP이면 모든 연결을 한 번 따라잡은 뒤 되돌린다(research R5, T035).
 */
data class RealtimeConnectionChanged(
    val state: RealtimeConnection,
)

/**
 * Redis 장애 판정(research R5). [probe]를 5초마다 부르고(`RealtimeConnectionProbe`), PING이 연속
 * [failureThreshold]번 실패하면 DOWN, DOWN에서 한 번 성공하면 UP이다. 바뀔 때만 [RealtimeConnectionChanged]를 낸다.
 *
 * 구독 컨테이너의 오류 처리기는 리스너 예외만 받고 Lettuce는 끊긴 구독을 조용히 다시 붙이므로, 구독 쪽 신호로는 장애를
 * 알 수 없다. 그래서 같은 Redis에 직접 PING을 보낸다. 처음 상태는 UP이다(기동 때 Redis가 없으면 두 번 만에 DOWN).
 */
class RealtimeConnectionState(
    private val ping: () -> Boolean,
    private val events: ApplicationEventPublisher,
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
) {
    private val state = AtomicReference(RealtimeConnection.UP)
    private val failures = AtomicInteger()

    val current: RealtimeConnection
        get() = state.get()

    fun probe() {
        // PING이 어떤 식으로 실패하든(예외 포함) 실패 한 번으로 센다
        val ok = runCatching(ping).getOrDefault(false)
        if (ok) {
            failures.set(0)
            change(RealtimeConnection.UP)
        } else if (failures.incrementAndGet() >= failureThreshold) {
            change(RealtimeConnection.DOWN)
        }
    }

    private fun change(next: RealtimeConnection) {
        if (state.getAndSet(next) != next) {
            events.publishEvent(RealtimeConnectionChanged(next))
        }
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 2

        /** [connectionFactory]로 PING을 보내 PONG이면 true. */
        fun redisPing(connectionFactory: RedisConnectionFactory): () -> Boolean =
            { connectionFactory.connection.use { it.ping() } == "PONG" }
    }
}
