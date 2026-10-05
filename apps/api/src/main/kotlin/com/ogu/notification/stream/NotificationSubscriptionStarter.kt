package com.ogu.notification.stream

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.SmartLifecycle
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 알림 신호 구독 컨테이너를 시작한다(research R5).
 *
 * `RedisMessageListenerContainer.start()`는 첫 구독이 실패하면 예외를 던지고 다시 시도하지 않는다. 그대로 두면 Redis가
 * 내려가 있을 때 API가 뜨지 못하므로, 여기서 실패를 받아 컨테이너를 멈추고 [NotificationRedisConfig.RECOVERY_INTERVAL]
 * 뒤에 다시 시작한다. 한 번 붙은 뒤의 끊김은 컨테이너가 같은 주기로 스스로 복구한다.
 */
@Component
class NotificationSubscriptionStarter(
    @param:Qualifier(NotificationRedisConfig.LISTENER_CONTAINER) private val container: RedisMessageListenerContainer,
) : SmartLifecycle {
    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    override fun start() {
        scheduler =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "notification-subscription").apply { isDaemon = true }
            }
        trySubscribe()
    }

    override fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        container.stop()
    }

    override fun isRunning(): Boolean = scheduler != null

    @Suppress("TooGenericExceptionCaught") // 어떤 구독 실패든 기동을 막지 않고 다시 시도한다
    private fun trySubscribe() {
        val current = scheduler ?: return
        try {
            container.start()
            log.info("알림 신호 채널({})을 구독했습니다", NotificationSignal.CHANNEL)
        } catch (e: RuntimeException) {
            container.stop()
            log.warn(
                "알림 신호 채널을 구독하지 못했습니다. {}초 뒤 다시 시도합니다: {}",
                NotificationRedisConfig.RECOVERY_INTERVAL.seconds,
                e.message,
            )
            // 그사이 stop()으로 멈췄으면 예약이 거절된다. 그때는 다시 시도할 필요가 없다
            runCatching {
                current.schedule(
                    ::trySubscribe,
                    NotificationRedisConfig.RECOVERY_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NotificationSubscriptionStarter::class.java)
    }
}
