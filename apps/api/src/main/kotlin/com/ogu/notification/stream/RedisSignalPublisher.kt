package com.ogu.notification.stream

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 알림 신호를 Redis 채널로 보낸다(data-model 생성 규칙 5, research R5).
 *
 * - 트랜잭션 안에서 부르면 커밋 뒤 훅(`afterCommit`)에 예약한다. 구독자가 아직 커밋되지 않은 알림을 찾다가 못 보는 일이 없고,
 *   롤백되면 아무것도 나가지 않는다. 트랜잭션 밖이면 바로 보낸다.
 * - 실제 전송은 전용 실행기([NotificationRedisConfig.SIGNAL_EXECUTOR], 스레드 하나)에서 한다. Redis에 붙을 수 없을 때 연결
 *   시도가 요청이나 이벤트 리스너 스레드를 붙잡지 않게 하기 위해서다. 한 줄로 차례대로 보내므로 신호 순서도 그대로다.
 * - 실패하면 WARN만 남긴다. 알림은 이미 커밋됐고, 놓친 신호는 안전망 따라잡기와 재연결 재전송이 메운다.
 */
@Component
class RedisSignalPublisher(
    private val redis: StringRedisTemplate,
    @param:Qualifier(NotificationRedisConfig.SIGNAL_EXECUTOR) private val executor: Executor,
) {
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
            executor.execute { send(signal) }
        } catch (e: RejectedExecutionException) {
            log.warn("알림 신호를 보내지 못했습니다(대기열 가득 참): {}", signal.encode(), e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Redis 클라이언트 예외 종류와 상관없이 호출한 쪽을 실패시키지 않는다
    private fun send(signal: NotificationSignal) {
        try {
            redis.convertAndSend(NotificationSignal.CHANNEL, signal.encode())
        } catch (e: RuntimeException) {
            log.warn("알림 신호를 Redis로 보내지 못했습니다: {} ({})", signal.encode(), e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisSignalPublisher::class.java)
    }
}
