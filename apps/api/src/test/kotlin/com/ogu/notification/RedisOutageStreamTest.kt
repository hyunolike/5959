package com.ogu.notification

import com.ogu.monster.MonsterApi
import com.ogu.notification.stream.RealtimeConnection
import com.ogu.notification.stream.RealtimeConnectionState
import com.ogu.notification.stream.SafetyDrain
import com.ogu.support.AppInstance
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.FixedHostPortGenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.time.Duration

/**
 * T026: Redis 장애 동안의 실시간 알림(research R5). 정해 둔 포트에 Redis를 띄웠다 멈췄다 하며 애플리케이션 하나를 그대로 둔다.
 * 장애 판정(PING 연속 2번 실패)과 줄어든 안전망 주기는 테스트에서 짧게 둔다(탐지 0.3초, 안전망 1초). 평소 안전망 주기는
 * 60초 그대로라, 그보다 훨씬 짧은 시간에 온 알림은 Redis 신호로 온 것이다.
 *
 * 테스트는 순서대로 이어진다: Redis 없이 기동 → Redis가 뜨면 구독 복구 → Redis 멈춤 → 멈춘 동안 다시 붙기 → 다시 띄움.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RedisOutageStreamTest {
    private val redisPort = ServerSocket(0).use { it.localPort }
    private val postgres =
        PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
    private var redis: FixedPortRedis? = null
    private lateinit var app: AppInstance
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var tickets: StreamTickets
    private lateinit var support: NotificationTestSupport
    private val streams = mutableListOf<SseStream>()

    private lateinit var author: TestMember
    private lateinit var fan: TestMember
    private var postId: Long = 0

    @BeforeAll
    fun startWithoutRedis() {
        postgres.start()
        app =
            AppInstance.start(
                mapOf(
                    "spring.datasource.url" to postgres.jdbcUrl,
                    "spring.datasource.username" to postgres.username,
                    "spring.datasource.password" to postgres.password,
                    "spring.data.redis.url" to "redis://localhost:$redisPort",
                    "ogu.notification.probe-interval" to "300ms",
                    "ogu.notification.outage-drain-interval" to "1s",
                ),
            )
        members = MemberFixture(app.mockMvc)
        loop = CoreLoopFixture(app.mockMvc, app.bean<JdbcTemplate>(), app.bean<MonsterApi>())
        tickets = StreamTickets(app.mockMvc)
        support = NotificationTestSupport(app.bean(), app.bean(), app.bean())
        author = members.onboarded()
        fan = members.onboarded()
        postId = loop.postWithoutMonster(author)
    }

    @AfterAll
    fun stopAll() {
        runCatching { app.close() }
        redis?.stop()
        postgres.stop()
    }

    @AfterEach
    fun closeStreams() {
        streams.forEach(SseStream::close)
        streams.clear()
    }

    @Test
    @Order(1)
    fun `Redis가 내려간 상태에서도 애플리케이션이 기동하고 구독은 복구 주기로 다시 시도한다`() {
        assertHealthUp()
        await().atMost(DETECT).until { state().current == RealtimeConnection.DOWN }
        assertThat(drain().currentInterval()).isEqualTo(OUTAGE_INTERVAL)

        startRedis()

        await().atMost(RECOVER).until { drain().currentInterval() == NORMAL_INTERVAL }
        // 구독 컨테이너가 다시 붙었다: 안전망(60초)을 기다리지 않고 신호로 온다
        val stream = open(author)
        val commentId = loop.comment(fan, postId, "Redis가 뜬 뒤")
        await().atMost(RECOVER).until {
            stream.notifications().any {
                it
                    .json()
                    .get("notification")
                    .get("commentId")
                    .asLong() == commentId
            }
        }
    }

    @Test
    @Order(2)
    fun `Redis 컨테이너를 멈춘 상태에서 공감과 댓글 API가 성공하고 알림이 DB에 있으며 열린 스트림이 줄어든 안전망 주기 안에 받는다`() {
        val stream = open(author, lastEventId = support.lastSeq(author.id))
        val liker = members.onboarded()

        stopRedis()
        await().atMost(DETECT).until { drain().currentInterval() == OUTAGE_INTERVAL }

        val commentId = loop.comment(fan, postId, "Redis가 멈춘 동안")
        loop.likePost(liker, postId).andExpect(status().isOk)
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
            support.notificationsOf(author.id).any { it.commentId == commentId } &&
                support.notificationsOf(author.id, "POST_LIKE").isNotEmpty()
        }
        unreadCount(author)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.latestSeq").value(support.lastSeq(author.id)))

        // 줄어든 안전망 주기(1초)에 한두 주기 여유를 둔다
        val received = stream.awaitNotifications(2, OUTAGE_DELIVERY)
        assertThat(
            received.map {
                it
                    .json()
                    .get("notification")
                    .get("type")
                    .asString()
            },
        ).containsExactlyInAnyOrder("POST_COMMENT", "POST_LIKE")
        assertHealthUp()
    }

    @Test
    @Order(3)
    fun `US1-AC6 Redis 없이 다시 붙은 연결도 lastEventId 이후를 빠짐없이 받는다`() {
        assertThat(state().current).isEqualTo(RealtimeConnection.DOWN)
        val lastSeen = support.lastSeq(author.id)

        repeat(MISSED) { loop.comment(fan, postId, "끊긴 동안 $it") }
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until { support.lastSeq(author.id) == lastSeen + MISSED }
        val expected = support.notificationsOf(author.id).filter { it.seq > lastSeen }

        val stream = open(author, lastEventId = lastSeen)

        val received = stream.awaitNotifications(expected.size)
        assertThat(received.map { it.id!!.toLong() }).isEqualTo(expected.map { it.seq })
        assertThat(received.map { it.id }).doesNotHaveDuplicates()
    }

    @Test
    @Order(4)
    fun `Redis를 다시 띄우면 구독이 돌아오고 안전망 주기가 원래대로 바뀐다`() {
        val stream = open(author, lastEventId = support.lastSeq(author.id))

        startRedis()

        await().atMost(RECOVER).until { drain().currentInterval() == NORMAL_INTERVAL }
        assertThat(state().current).isEqualTo(RealtimeConnection.UP)
        val commentId = loop.comment(fan, postId, "다시 뜬 뒤")
        await().atMost(RECOVER).until {
            stream.notifications().any {
                it
                    .json()
                    .get("notification")
                    .get("commentId")
                    .asLong() == commentId
            }
        }
        assertThat(stream.notifications()).hasSize(1)
    }

    private fun open(
        member: TestMember,
        lastEventId: Long? = null,
    ): SseStream = SseTestClient.connect(app.port, tickets.issue(member), lastEventId).also { streams += it }

    private fun unreadCount(member: TestMember): ResultActions {
        val request = get("/api/v1/notifications/unread-count").bearer(member.accessToken)
        return app.mockMvc.perform(request)
    }

    private fun assertHealthUp() {
        app.mockMvc
            .perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    private fun state(): RealtimeConnectionState = app.bean()

    private fun drain(): SafetyDrain = app.bean()

    private fun startRedis() {
        redis = FixedPortRedis(redisPort).also { it.start() }
    }

    private fun stopRedis() {
        redis?.stop()
        redis = null
    }

    private companion object {
        const val MISSED = 3
        val NORMAL_INTERVAL: Duration = Duration.ofSeconds(60)
        val OUTAGE_INTERVAL: Duration = Duration.ofSeconds(1)
        val DETECT: Duration = Duration.ofSeconds(15)
        val OUTAGE_DELIVERY: Duration = Duration.ofSeconds(4)

        /** Lettuce의 재연결 간격이 지수로 늘어나므로(최대 30초) 넉넉히 둔다. */
        val RECOVER: Duration = Duration.ofSeconds(45)
    }
}

/** 정해 둔 호스트 포트에 뜨는 Redis. 멈췄다 다시 띄워도 애플리케이션이 같은 주소로 다시 붙는다. */
@Suppress("DEPRECATION") // 고정 포트가 이 시나리오의 핵심이다
private class FixedPortRedis(
    port: Int,
) : FixedHostPortGenericContainer<FixedPortRedis>("redis:7.4-alpine") {
    init {
        withFixedExposedPort(port, REDIS_PORT)
    }

    private companion object {
        const val REDIS_PORT = 6379
    }
}
