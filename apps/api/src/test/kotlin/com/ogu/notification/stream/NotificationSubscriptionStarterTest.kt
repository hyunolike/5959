package com.ogu.notification.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.listener.RedisMessageListenerContainer

class NotificationSubscriptionStarterTest {
    @Test
    fun `구독을 시작하는 사이에 멈추라는 요청이 오면 시작이 끝난 뒤 다시 멈춘다`() {
        lateinit var starter: NotificationSubscriptionStarter
        val container =
            object : RedisMessageListenerContainer() {
                @Volatile
                var running = false

                override fun start() {
                    // 시작이 끝나기 전에 다른 스레드의 stop()이 먼저 지나간 상황
                    starter.stop()
                    running = true
                }

                override fun stop() {
                    running = false
                }

                override fun isRunning(): Boolean = running
            }
        starter = NotificationSubscriptionStarter(container)

        starter.start()

        assertThat(container.isRunning).isFalse()
        assertThat(starter.isRunning).isFalse()
    }
}
