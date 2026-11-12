package com.ogu.notification.stream

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 알림 신호를 이 서버의 [NotificationSignalHandler]들(SSE 연결 허브)에게 넘긴다(018 research R4). 서버가 한 대라 서버끼리
 * 신호를 주고받는 저장소가 없다.
 *
 * - 트랜잭션 안에서 부르면 커밋 뒤 훅(`afterCommit`)에 예약한다. 허브가 아직 커밋되지 않은 알림을 찾다가 못 보는 일이 없고,
 *   롤백되면 아무것도 나가지 않는다. 트랜잭션 밖이면 바로 넘긴다.
 * - 넘기는 일은 전용 스레드 하나([LocalSignalConfig.SIGNAL_EXECUTOR])에서 한다. 요청이나 이벤트 리스너 스레드를 붙잡지 않고,
 *   한 줄로 차례대로 넘기므로 신호 순서도 그대로다.
 * - 대기열(1만 개)이 가득 차면 버리고 WARN을 1분에 한 번만 남긴다(나머지는 DEBUG). 신호는 힌트라서, 놓친 것은 안전망
 *   따라잡기([SafetyDrain])와 재연결 재전송이 메운다. 처리기 하나가 실패해도 다른 처리기는 받는다.
 */
@Component
class LocalSignalPublisher(
    private val handlers: List<NotificationSignalHandler>,
    @param:Qualifier(LocalSignalConfig.SIGNAL_EXECUTOR) private val executor: Executor,
) {
    private val warnings = LogThrottle(WARN_INTERVAL)

    fun publish(signal: NotificationSignal) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = dispatch(signal)
                },
            )
        } else {
            dispatch(signal)
        }
    }

    private fun dispatch(signal: NotificationSignal) {
        try {
            executor.execute { deliver(signal) }
        } catch (e: RejectedExecutionException) {
            if (warnings.tryAcquire()) {
                log.warn("알림 신호를 버립니다(대기열 가득 참, 이 경고는 1분에 한 번): {} ({})", signal, e.message)
            } else {
                log.debug("알림 신호를 버립니다(대기열 가득 참): {}", signal)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 처리기 하나의 실패가 신호 스레드나 다른 처리기로 번지지 않게 한다
    private fun deliver(signal: NotificationSignal) {
        handlers.forEach { handler ->
            try {
                handler.onSignal(signal)
            } catch (e: RuntimeException) {
                log.warn("알림 신호 처리에 실패했습니다: {}", signal, e)
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(LocalSignalPublisher::class.java)
        val WARN_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}

/** 알림 신호를 넘기는 전용 스레드 하나와 대기열 1만 개. */
@Configuration(proxyBeanMethods = false)
class LocalSignalConfig {
    @Bean(SIGNAL_EXECUTOR)
    fun notificationSignalExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 1
            queueCapacity = SIGNAL_QUEUE_CAPACITY
            setThreadNamePrefix("notification-signal-")
            // 신호는 힌트라서 끝낼 때 남은 것을 기다리지 않는다(연결도 함께 닫힌다)
            setWaitForTasksToCompleteOnShutdown(false)
        }

    companion object {
        const val SIGNAL_EXECUTOR = "notificationSignalExecutor"
        const val SIGNAL_QUEUE_CAPACITY = 10_000
    }
}
