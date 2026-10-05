package com.ogu.notification.stream

import com.ogu.TestcontainersConfiguration
import io.lettuce.core.ClientOptions
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * T011: 알림 신호는 커밋 뒤에만 Redis 채널로 나간다(data-model 생성 규칙 5, research R5). 앱의 구독 컨테이너에
 * 테스트 리스너를 붙여 실제로 채널에 나간 신호를 모은다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RedisSignalPublisherTests {
    @Autowired
    lateinit var publisher: RedisSignalPublisher

    @Autowired
    lateinit var redis: StringRedisTemplate

    @Autowired
    lateinit var container: RedisMessageListenerContainer

    @Autowired
    lateinit var connectionFactory: RedisConnectionFactory

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    private val received = CopyOnWriteArrayList<String>()
    private val listener = MessageListener { message, _ -> received += String(message.body) }

    @BeforeEach
    fun subscribe() {
        container.addMessageListener(listener, ChannelTopic(NotificationSignal.CHANNEL))
        // 구독이 실제로 걸릴 때까지 표지를 보내 본다
        val probe = "probe-${UUID.randomUUID()}"
        await().atMost(Duration.ofSeconds(10)).until {
            redis.convertAndSend(NotificationSignal.CHANNEL, probe)
            received.contains(probe)
        }
        received.clear()
    }

    @AfterEach
    fun unsubscribe() {
        container.removeMessageListener(listener)
    }

    @Test
    fun `트랜잭션 밖에서 발행하면 바로 채널에 나간다`() {
        publisher.publish(NotificationSignal.New(memberId = 11L, seq = 1L))

        await().atMost(Duration.ofSeconds(5)).until { received.contains("11:n:1") }
    }

    @Test
    fun `트랜잭션 안에서 발행하면 커밋 전에는 나가지 않고 커밋 뒤에 나간다`() {
        transactionTemplate.executeWithoutResult {
            publisher.publish(NotificationSignal.New(memberId = 12L, seq = 3L))

            // 같은 채널에 바로 보낸 표지가 도착할 때까지도 알림 신호는 오지 않아야 한다
            val marker = "marker-${UUID.randomUUID()}"
            redis.convertAndSend(NotificationSignal.CHANNEL, marker)
            await().atMost(Duration.ofSeconds(5)).until { received.contains(marker) }
            assertThat(received).doesNotContain("12:n:3")
        }

        await().atMost(Duration.ofSeconds(5)).until { received.contains("12:n:3") }
    }

    @Test
    fun `롤백된 트랜잭션에서 예약한 신호는 나가지 않는다`() {
        transactionTemplate.executeWithoutResult { status ->
            publisher.publish(NotificationSignal.Read(memberId = 13L))
            status.setRollbackOnly()
        }

        // 신호는 한 줄로 차례대로 나가므로 뒤에 보낸 신호가 도착했으면 앞의 신호는 영영 오지 않는다
        publisher.publish(NotificationSignal.Read(memberId = 14L))
        await().atMost(Duration.ofSeconds(5)).until { received.contains("14:r") }
        assertThat(received).doesNotContain("13:r")
    }

    @Test
    fun `Redis 연결이 끊긴 동안 명령은 타임아웃까지 기다리지 않고 바로 거절된다`() {
        val options = (connectionFactory as LettuceConnectionFactory).clientConfiguration.clientOptions

        assertThat(options).isPresent
        assertThat(options.get().disconnectedBehavior).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
    }

    @Test
    fun `구독 컨테이너의 복구 주기는 5초다`() {
        assertThat(NotificationRedisConfig.RECOVERY_INTERVAL).isEqualTo(Duration.ofSeconds(5))
    }
}
