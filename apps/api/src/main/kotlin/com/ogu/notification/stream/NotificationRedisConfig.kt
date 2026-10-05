package com.ogu.notification.stream

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration

/**
 * 알림 신호의 Redis 배선(research R5): 발행 전용 실행기와 채널 구독 컨테이너.
 *
 * 구독이 끊기면 컨테이너가 [RECOVERY_INTERVAL]마다 다시 붙는다. 컨테이너는 스스로 시작하지 않는다. 첫 구독이 실패하면
 * 컨테이너가 예외를 던져 기동을 막기 때문에, [NotificationSubscriptionStarter]가 대신 시작하고 실패하면 같은 주기로 다시
 * 시도한다(Redis 없이도 API는 뜬다). 빈 이름이 Spring Boot의 기본 컨테이너와 같아 그것을 대신한다.
 */
@Configuration(proxyBeanMethods = false)
class NotificationRedisConfig {
    @Bean(SIGNAL_EXECUTOR)
    fun notificationSignalExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 1
            queueCapacity = SIGNAL_QUEUE_CAPACITY
            setThreadNamePrefix("notification-signal-")
            // 신호는 힌트라서 대기열이 가득 차면 버린다(RedisSignalPublisher가 WARN을 남긴다)
            setWaitForTasksToCompleteOnShutdown(false)
        }

    @Bean(LISTENER_CONTAINER)
    fun notificationListenerContainer(
        connectionFactory: RedisConnectionFactory,
        subscriber: RedisSignalSubscriber,
    ): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(connectionFactory)
            isAutoStartup = false
            setRecoveryInterval(RECOVERY_INTERVAL.toMillis())
            setErrorHandler { e ->
                log.warn("알림 신호 구독에 실패했습니다. {}초 뒤 다시 붙습니다: {}", RECOVERY_INTERVAL.seconds, e.message)
            }
            addMessageListener(subscriber, ChannelTopic(NotificationSignal.CHANNEL))
        }

    companion object {
        const val SIGNAL_EXECUTOR = "notificationSignalExecutor"
        const val LISTENER_CONTAINER = "redisMessageListenerContainer"
        val RECOVERY_INTERVAL: Duration = Duration.ofSeconds(5)
        private const val SIGNAL_QUEUE_CAPACITY = 10_000
        private val log = LoggerFactory.getLogger(NotificationRedisConfig::class.java)
    }
}
