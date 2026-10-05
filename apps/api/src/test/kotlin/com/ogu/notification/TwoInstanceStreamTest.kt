package com.ogu.notification

import com.ogu.monster.MonsterApi
import com.ogu.support.AppInstance
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import com.redis.testcontainers.RedisContainer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * T025: 서버 두 대(SC-003, US1-AC6, AC7, research R5). 같은 Testcontainers Postgres와 Redis에 애플리케이션 두 개(A, B)를
 * 다른 포트로 띄운다. 알림을 만든 서버와 연결이 붙은 서버가 달라도 Redis 신호로 바로 오고, 한 대가 내려가도 다른 서버에
 * 마지막 번호로 붙으면 DB에서 빠짐없이 다시 받는다. 안전망 주기(60초)보다 훨씬 짧게 기다려 신호로 왔는지 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoInstanceStreamTest {
    private val postgres =
        PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
    private val redis = RedisContainer(DockerImageName.parse("redis:7.4-alpine"))
    private lateinit var a: AppInstance
    private lateinit var b: AppInstance
    private val streams = mutableListOf<SseStream>()

    @BeforeAll
    fun startInstances() {
        postgres.start()
        redis.start()
        a = AppInstance.start(properties())
        b = AppInstance.start(properties())
    }

    @AfterAll
    fun stopInstances() {
        listOf(b, a).forEach { runCatching { it.close() } }
        redis.stop()
        postgres.stop()
    }

    @AfterEach
    fun closeStreams() {
        streams.forEach(SseStream::close)
    }

    @Test
    fun `US1-AC7 A에 붙은 회원에게 B에서 만든 알림이 온다`() {
        val instanceA = a
        val membersB = MemberFixture(b.mockMvc)
        val author = membersB.onboarded()
        val fan = membersB.onboarded()
        val loopB = loop(b)
        val postId = loopB.postWithoutMonster(author)
        // 표는 B에서 받고 A에 붙는다(표는 DB에 있어 어느 서버든 소비할 수 있다)
        val stream = connect(instanceA, StreamTickets(b.mockMvc).issue(author))
        awaitSubscribed(b, instanceA)

        val commentId = loopB.comment(fan, postId)

        val event = stream.awaitNotifications(1, SIGNAL_LIMIT).single()
        assertThat(
            event
                .json()
                .get("notification")
                .get("commentId")
                .asLong(),
        ).isEqualTo(commentId)
    }

    @Test
    fun `US1-AC6 A를 닫고 B에 마지막 번호로 붙으면 그 사이 알림이 빠짐없이 중복 없이 온다`() {
        val instanceA = AppInstance.start(properties())
        try {
            val members = MemberFixture(b.mockMvc)
            val author = members.onboarded()
            val fan = members.onboarded()
            val likers = (1..3).map { members.onboarded() }
            val loopB = loop(b)
            val postId = loopB.postWithoutMonster(author)
            val onA = connect(instanceA, StreamTickets(instanceA.mockMvc).issue(author))
            awaitSubscribed(b, instanceA)
            loopB.comment(fan, postId, "A가 받은 댓글")
            val lastSeen =
                onA
                    .awaitNotifications(1, SIGNAL_LIMIT)
                    .single()
                    .id!!
                    .toLong()

            instanceA.close()
            onA.awaitEnded()
            repeat(MISSED_COMMENTS) { loopB.comment(fan, postId, "A가 내려간 동안 $it") }
            likers.forEach { loopB.likePost(it, postId).andExpect(status().isOk) }
            val jdbc = b.bean<JdbcTemplate>()
            val support = NotificationTestSupport(jdbc, b.bean(), b.bean())
            await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
                val rows = support.notificationsOf(author.id)
                val grouped = rows.any { it.type == "POST_LIKE" && it.actorCount == likers.size }
                rows.size == 1 + MISSED_COMMENTS + 1 && grouped
            }
            support.awaitListenersIdle(postId)
            val expected = support.notificationsOf(author.id).filter { it.seq > lastSeen }

            val onB = connect(b, StreamTickets(b.mockMvc).issue(author), lastEventId = lastSeen)
            onB.awaitNotifications(expected.size)
            // 표지 알림까지 받아 뒤늦은 중복이 없는지 본다
            loopB.comment(fan, postId, "표지")
            val received = onB.awaitNotifications(expected.size + 1).dropLast(1)

            assertThat(received.map { it.id!!.toLong() }).isEqualTo(expected.map { it.seq })
            assertThat(
                received.map {
                    it
                        .json()
                        .get("notification")
                        .get("notificationId")
                        .asLong()
                },
            ).isEqualTo(expected.map { it.id })
            assertThat(received.map { it.id }).doesNotHaveDuplicates()
            assertThat(onB.notifications()).hasSize(expected.size + 1)
        } finally {
            runCatching { instanceA.close() }
        }
    }

    private fun connect(
        instance: AppInstance,
        ticket: String,
        lastEventId: Long? = null,
    ): SseStream = SseTestClient.connect(instance.port, ticket, lastEventId).also { streams += it }

    /** 두 서버가 모두 알림 채널을 구독했을 때까지 기다린다(B가 표지를 보내 A가 받는지 본다). */
    private fun awaitSubscribed(
        publisher: AppInstance,
        subscriber: AppInstance,
    ) {
        val redis = publisher.bean<StringRedisTemplate>()
        NotificationTestSupport(subscriber.bean(), redis, subscriber.bean<RedisMessageListenerContainer>())
            .apply {
                subscribeSignals()
                unsubscribeSignals()
            }
    }

    private fun loop(instance: AppInstance): CoreLoopFixture =
        CoreLoopFixture(instance.mockMvc, instance.bean(), instance.bean<MonsterApi>())

    private fun properties(): Map<String, String> =
        mapOf(
            "spring.datasource.url" to postgres.jdbcUrl,
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to postgres.password,
            "spring.data.redis.url" to redis.redisURI,
        )

    private companion object {
        const val MISSED_COMMENTS = 5
        val SIGNAL_LIMIT: Duration = Duration.ofSeconds(10)
    }
}
