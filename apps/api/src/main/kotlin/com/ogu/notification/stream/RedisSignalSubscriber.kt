package com.ogu.notification.stream

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.stereotype.Component

/**
 * Redis 채널 [NotificationSignal.CHANNEL]의 신호를 해석해 이 인스턴스의 [NotificationSignalHandler]들에게 넘긴다(research R5).
 * 형식이 틀린 신호는 버린다. 처리기 하나가 실패해도 다른 처리기는 받는다.
 */
@Component
class RedisSignalSubscriber(
    private val handlers: List<NotificationSignalHandler>,
) : MessageListener {
    @Suppress("TooGenericExceptionCaught") // 처리기 하나의 실패가 구독 스레드나 다른 처리기로 번지지 않게 한다
    override fun onMessage(
        message: Message,
        pattern: ByteArray?,
    ) {
        val raw = String(message.body, Charsets.UTF_8)
        val signal = NotificationSignal.parse(raw)
        if (signal == null) {
            log.debug("형식이 틀린 알림 신호를 버립니다: {}", raw)
            return
        }
        handlers.forEach { handler ->
            try {
                handler.onSignal(signal)
            } catch (e: RuntimeException) {
                log.warn("알림 신호 처리에 실패했습니다: {}", raw, e)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisSignalSubscriber::class.java)
    }
}
