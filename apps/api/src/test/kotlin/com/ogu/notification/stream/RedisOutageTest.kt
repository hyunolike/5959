package com.ogu.notification.stream

import com.ogu.shared.config.RedisClientConfig
import com.redis.testcontainers.RedisContainer
import io.lettuce.core.ClientOptions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.FixedHostPortGenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * T011: Redis가 멈춰도 알림을 쓴 쪽(요청, 이벤트 리스너)은 성공한다(research R5). 공용 테스트 Redis를 멈추면 다른
 * 테스트가 깨지므로 이 테스트만 쓰는 Redis를 따로 띄운다.
 */
class RedisOutageTest {
    @Test
    fun `Redis가 멈춘 상태에서 발행해도 호출한 쪽은 예외 없이 바로 돌아온다`() {
        RedisContainer(DockerImageName.parse("redis:7.4-alpine")).use { redisServer ->
            redisServer.start()
            val factory = connectionFactory(redisServer.redisHost, redisServer.redisPort)
            try {
                val template = StringRedisTemplate(factory)
                assertThat(template.connectionFactory!!.connection.ping()).isEqualTo("PONG")

                redisServer.stop()
                // 끊긴 것을 클라이언트가 알아챌 때까지 기다린다(그 전에는 명령이 1초 타임아웃까지 간다)
                await().atMost(Duration.ofSeconds(15)).until { pingFails(template) }

                // 같은 스레드에서 보내게 해, 발행 경로 자체가 기다리지 않는지 본다
                val publisher = RedisSignalPublisher(template, DIRECT)
                val elapsed =
                    measure {
                        assertThatCode { publisher.publish(NotificationSignal.New(memberId = 1L, seq = 1L)) }
                            .doesNotThrowAnyException()
                    }
                assertThat(elapsed).isLessThan(Duration.ofMillis(500))
            } finally {
                factory.destroy()
            }
        }
    }

    @Test
    fun `Redis에 붙을 수 없어도 발행한 쪽은 연결 시도를 기다리지 않는다`() {
        // 응답 없는 주소라 연결 시도가 연결 타임아웃까지 걸린다. 그 시간을 발행한 쪽이 기다리면 안 된다
        val factory = connectionFactory(UNREACHABLE_HOST, REDIS_PORT)
        val executor = NotificationRedisConfig().notificationSignalExecutor().apply { afterPropertiesSet() }
        try {
            val publisher = RedisSignalPublisher(StringRedisTemplate(factory), executor)

            val elapsed =
                measure {
                    repeat(3) { seq ->
                        assertThatCode { publisher.publish(NotificationSignal.New(memberId = 2L, seq = seq + 1L)) }
                            .doesNotThrowAnyException()
                    }
                }
            assertThat(elapsed).isLessThan(Duration.ofMillis(200))
        } finally {
            executor.shutdown()
            factory.destroy()
        }
    }

    @Test
    fun `Redis 없이 시작해도 구독은 기동을 막지 않고, Redis가 뜨면 다시 붙어 신호를 받는다`() {
        val port = unusedPort()
        val factory = connectionFactory("localhost", port)
        val received = CopyOnWriteArrayList<NotificationSignal>()
        val container =
            NotificationRedisConfig()
                .notificationListenerContainer(
                    factory,
                    RedisSignalSubscriber(listOf(NotificationSignalHandler(received::add))),
                ).apply { afterPropertiesSet() }
        val starter = NotificationSubscriptionStarter(container)
        try {
            val elapsed = measure { assertThatCode { starter.start() }.doesNotThrowAnyException() }
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5))
            assertThat(starter.isRunning).isTrue()

            LateRedis(port).use { late ->
                late.start()
                val template = StringRedisTemplate(factory)
                await().atMost(Duration.ofSeconds(20)).until {
                    runCatching { template.convertAndSend(NotificationSignal.CHANNEL, "3:r") }
                    received.contains(NotificationSignal.Read(3L))
                }
            }
        } finally {
            starter.stop()
            container.destroy()
            factory.destroy()
        }
    }

    @Test
    fun `PING 탐지기는 Redis가 없으면 두 번 만에 DOWN, 뜨면 UP으로 바꾼다`() {
        val port = unusedPort()
        val factory = connectionFactory("localhost", port)
        val events = CopyOnWriteArrayList<Any>()
        val state = RealtimeConnectionState(RealtimeConnectionState.redisPing(factory), { events += it })
        try {
            repeat(2) { state.probe() }
            assertThat(state.current).isEqualTo(RealtimeConnection.DOWN)

            LateRedis(port).use { late ->
                late.start()
                await().atMost(Duration.ofSeconds(20)).until {
                    state.probe()
                    state.current == RealtimeConnection.UP
                }
            }
            assertThat(events).containsExactly(
                RealtimeConnectionChanged(RealtimeConnection.DOWN),
                RealtimeConnectionChanged(RealtimeConnection.UP),
            )
        } finally {
            factory.destroy()
        }
    }

    private fun connectionFactory(
        host: String,
        port: Int,
    ): LettuceConnectionFactory {
        val options = ClientOptions.builder()
        RedisClientConfig().rejectCommandsWhileDisconnected().customize(options)
        val client =
            LettuceClientConfiguration
                .builder()
                .clientOptions(options.build())
                .commandTimeout(Duration.ofSeconds(1))
                .build()
        return LettuceConnectionFactory(RedisStandaloneConfiguration(host, port), client).apply {
            afterPropertiesSet()
            start()
        }
    }

    private fun pingFails(template: StringRedisTemplate): Boolean {
        val ping = runCatching { template.connectionFactory!!.connection.ping() }
        return ping.isFailure
    }

    private fun measure(block: () -> Unit): Duration {
        val start = System.nanoTime()
        block()
        return Duration.ofNanos(System.nanoTime() - start)
    }

    private fun unusedPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    companion object {
        private val DIRECT = Executor { it.run() }
        private const val UNREACHABLE_HOST = "10.255.255.1"
        private const val REDIS_PORT = 6379
    }
}

/** 정해 둔 호스트 포트에 뒤늦게 뜨는 Redis. 클라이언트가 먼저 그 포트를 바라보고 있다가 다시 붙는지 본다. */
@Suppress("DEPRECATION") // 고정 포트가 이 시나리오의 핵심이다
private class LateRedis(
    port: Int,
) : FixedHostPortGenericContainer<LateRedis>("redis:7.4-alpine") {
    init {
        withFixedExposedPort(port, 6379)
    }
}
