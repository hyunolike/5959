package com.ogu.notification.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.DefaultMessage

class RedisSignalSubscriberTest {
    @Test
    fun `채널로 받은 신호를 해석해 처리기마다 넘긴다`() {
        val first = mutableListOf<NotificationSignal>()
        val second = mutableListOf<NotificationSignal>()
        val subscriber =
            RedisSignalSubscriber(listOf(NotificationSignalHandler(first::add), NotificationSignalHandler(second::add)))

        subscriber.onMessage(message("5:n:9"), null)
        subscriber.onMessage(message("5:r"), null)

        val expected = listOf(NotificationSignal.New(5L, 9L), NotificationSignal.Read(5L))
        assertThat(first).isEqualTo(expected)
        assertThat(second).isEqualTo(expected)
    }

    @Test
    fun `형식이 틀린 신호는 버리고, 처리기 하나가 실패해도 다른 처리기는 받는다`() {
        val received = mutableListOf<NotificationSignal>()
        val subscriber =
            RedisSignalSubscriber(
                listOf(NotificationSignalHandler { error("boom") }, NotificationSignalHandler(received::add)),
            )

        subscriber.onMessage(message("garbage"), null)
        subscriber.onMessage(message("6:r"), null)

        assertThat(received).containsExactly(NotificationSignal.Read(6L))
    }

    private fun message(body: String) = DefaultMessage(NotificationSignal.CHANNEL.toByteArray(), body.toByteArray())
}
