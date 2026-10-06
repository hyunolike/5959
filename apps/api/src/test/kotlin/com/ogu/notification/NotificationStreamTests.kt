package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.member.MemberApi
import com.ogu.monster.MonsterApi
import com.ogu.notification.stream.SseHub
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration

/**
 * T022: 실시간 알림 스트림(US1-AC1, AC7, AC8, research R2~R5). 실제 HTTP로 붙어 `text/event-stream`을 줄 단위로 읽는다.
 * 하트비트 주기는 테스트에서 줄인다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
// 이 클래스만의 설정이라 다른 테스트와 컨텍스트를 나누지 않는다. 끝나면 닫아 컨테이너와 메모리를 돌려준다
@DirtiesContext
@TestPropertySource(
    properties = [
        "ogu.notification.heartbeat=200ms",
        // 안전망이 대신 전달해 주지 못하게 길게 둔다. 기다리는 시간 안에는 Redis 신호만 알림을 보낼 수 있다
        "ogu.notification.safety-drain-interval=10m",
        "ogu.sse.allowed-origins=${NotificationStreamTests.WEB_ORIGIN}",
    ],
)
@ExtendWith(OutputCaptureExtension::class)
class NotificationStreamTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var hub: SseHub

    @MockitoSpyBean
    lateinit var memberApi: MemberApi

    @LocalServerPort
    var port: Int = 0

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var tickets: StreamTickets
    private val streams = mutableListOf<SseStream>()

    @BeforeEach
    fun setUp() {
        val mockMvc: MockMvc =
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        tickets = StreamTickets(mockMvc)
    }

    @AfterEach
    fun tearDown() {
        streams.forEach(SseStream::close)
    }

    @Test
    fun `US1-AC1 열린 스트림에 notification 이벤트가 id=seq와 unreadCount를 싣고 온다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.createPost(author, "[실패] 오늘 회의에서 말을 못 했어요")
        val stream = open(author)

        val commentId = loop.comment(fan, postId)

        val event = stream.awaitNotifications(1).single()
        val data = event.json()
        val notification = data.get("notification")
        val stored = jdbcTemplate.queryForMap("select id, seq from notification where receiver_id = ?", author.id)
        assertThat(event.id).isEqualTo(stored["seq"].toString())
        assertThat(notification.get("notificationId").asLong()).isEqualTo(stored["id"])
        assertThat(notification.get("seq").asLong()).isEqualTo(stored["seq"])
        assertThat(notification.get("type").asString()).isEqualTo("POST_COMMENT")
        assertThat(notification.get("postId").asLong()).isEqualTo(postId)
        assertThat(notification.get("post").get("contentPreview").asString()).isEqualTo("[실패] 오늘 회의에서 말을 못 했어요")
        assertThat(notification.get("commentId").asLong()).isEqualTo(commentId)
        assertThat(notification.get("actor").get("id").asLong()).isEqualTo(fan.id)
        assertThat(notification.get("actor").get("nickname").asString()).isNotBlank()
        assertThat(notification.get("actorCount").asInt()).isEqualTo(1)
        assertThat(notification.get("read").asBoolean()).isFalse()
        assertThat(data.get("unreadCount").asLong()).isEqualTo(1)
    }

    @Test
    fun `US1-AC7 같은 회원의 연결 두 개 모두 받는다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val first = open(author)
        val second = open(author)

        loop.comment(fan, postId)

        assertThat(first.awaitNotifications(1).single().id).isEqualTo(second.awaitNotifications(1).single().id)
    }

    @Test
    fun `US1-AC8 다른 회원의 알림은 오지 않는다`() {
        val author = members.onboarded()
        val other = members.onboarded()
        val fan = members.onboarded()
        val authorPost = loop.postWithoutMonster(author)
        val otherPost = loop.postWithoutMonster(other)
        val authorStream = open(author)
        val otherStream = open(other)

        loop.comment(fan, authorPost)
        authorStream.awaitNotifications(1)
        // 남의 알림이 먼저 왔다면 이 회원 자신의 알림보다 앞에 있다
        loop.comment(fan, otherPost)

        val received = otherStream.awaitNotifications(1)
        assertThat(received).hasSize(1)
        assertThat(
            received
                .single()
                .json()
                .get("notification")
                .get("postId")
                .asLong(),
        ).isEqualTo(otherPost)
        assertThat(authorStream.notifications()).hasSize(1)
    }

    @Test
    fun `응답 헤더는 text-event-stream, no-store, X-Accel-Buffering no다`() {
        val stream = open(members.onboarded())

        assertThat(stream.header("Content-Type")).startsWith("text/event-stream")
        assertThat(stream.header("Cache-Control")).isEqualTo("no-store")
        assertThat(stream.header("X-Accel-Buffering")).isEqualTo("no")
    }

    @Test
    fun `하트비트 주석 줄이 주기마다 온다`() {
        val stream = open(members.onboarded())

        await().atMost(AWAIT).until { stream.comments.size >= 2 }
        assertThat(stream.comments).allMatch { it == ": hb" }
    }

    @Test
    fun `6번째 연결이 오면 가장 오래된 연결이 닫힌다`() {
        val member = members.onboarded()
        val opened = (1..MAX_CONNECTIONS).map { open(member) }
        await().atMost(AWAIT).until { hub.connectionCount(member.id) == MAX_CONNECTIONS }

        val sixth = open(member)

        opened.first().awaitEnded()
        assertThat(opened.drop(1)).noneMatch { it.ended }
        assertThat(sixth.ended).isFalse()
        await().atMost(AWAIT).until { hub.connectionCount(member.id) == MAX_CONNECTIONS }
    }

    @Test
    fun `끊긴 연결은 허브에서 지운다`() {
        val member = members.onboarded()
        val stream = open(member)
        await().atMost(AWAIT).until { hub.connectionCount(member.id) == 1 }

        stream.close()

        // 다음 하트비트 쓰기가 실패하면 지운다
        await().atMost(AWAIT).until { hub.connectionCount(member.id) == 0 }
    }

    @Test
    fun `CORS는 허용한 웹 출처만 열고 자격 증명 헤더는 없다`() {
        val member = members.onboarded()

        val allowed = open(member, headers = mapOf("Origin" to WEB_ORIGIN))
        assertThat(allowed.status).isEqualTo(OK)
        assertThat(allowed.header("Access-Control-Allow-Origin")).isEqualTo(WEB_ORIGIN)
        assertThat(allowed.header("Access-Control-Allow-Credentials")).isNull()

        val denied = open(member, headers = mapOf("Origin" to "https://evil.example"))
        assertThat(denied.status).isEqualTo(FORBIDDEN)
        assertThat(denied.header("Access-Control-Allow-Origin")).isNull()
    }

    @Test
    fun `지금 번호보다 큰 lastEventId로 붙어도 새 알림을 받는다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val stream = open(author, lastEventId = Long.MAX_VALUE)

        val commentId = loop.comment(fan, postId)

        val event = stream.awaitNotifications(1).single()
        assertThat(
            event
                .json()
                .get("notification")
                .get("commentId")
                .asLong(),
        ).isEqualTo(commentId)
    }

    @Test
    fun `평범한 끊김은 경고 없이 정리된다`(output: CapturedOutput) {
        val member = members.onboarded()
        val opened = (1..MAX_CONNECTIONS).map { open(member) }
        await().atMost(AWAIT).until { hub.connectionCount(member.id) == MAX_CONNECTIONS }

        opened.forEach(SseStream::close)

        await().atMost(AWAIT).until { hub.connectionCount(member.id) == 0 }
        // 정리된 뒤 하트비트가 몇 번 더 돌 동안에도 조용해야 한다
        val other = open(members.onboarded())
        await().atMost(AWAIT).until { other.comments.size >= 3 }
        assertThat(output.out).doesNotContain("따라잡지 못해 닫습니다").doesNotContain("Unhandled exception")
    }

    @Test
    fun `허브가 멈추는 중에 온 연결은 바로 닫는다`() {
        val member = members.onboarded()
        val ticket = tickets.issue(member)
        hub.stop()
        try {
            val stream = SseTestClient.connect(port, ticket).also { streams += it }

            stream.awaitEnded()
            assertThat(hub.connectionCount(member.id)).isZero()
        } finally {
            hub.start()
        }
    }

    @Test
    fun `스트림에서 난 서버 오류도 JSON 오류 봉투로 온다`() {
        doThrow(IllegalStateException("DB에 붙을 수 없다")).`when`(memberApi).consumeStreamTicket(anyString())

        val stream = SseTestClient.connect(port, "any-ticket").also { streams += it }

        assertThat(stream.status).isEqualTo(INTERNAL_SERVER_ERROR)
        assertThat(stream.header("Content-Type")).startsWith("application/json")
        val body = stream.errorBody()
        assertThat(body.get("success").asBoolean()).isFalse()
        assertThat(body.get("error").get("code").asString()).isEqualTo("INTERNAL_ERROR")
    }

    private fun open(
        member: TestMember,
        lastEventId: Long? = null,
        headers: Map<String, String> = emptyMap(),
    ): SseStream = SseTestClient.connect(port, tickets.issue(member), lastEventId, headers).also { streams += it }

    /** 연결 수명이 짧은 설정. 다른 테스트의 스트림이 중간에 닫히지 않게 컨텍스트를 따로 둔다. */
    @Nested
    @TestPropertySource(properties = ["ogu.notification.connection-lifetime=1s"])
    inner class ConnectionLifetime {
        @Test
        fun `연결 수명이 지나면 서버가 스트림을 닫는다`() {
            val member = members.onboarded()
            val stream = open(member)
            assertThat(stream.status).isEqualTo(OK)

            stream.awaitEnded(Duration.ofSeconds(LIFETIME_LIMIT_SECONDS))
            await().atMost(AWAIT).until { hub.connectionCount(member.id) == 0 }
        }
    }

    companion object {
        const val WEB_ORIGIN = "http://web.ogu.test"
        private const val OK = 200
        private const val FORBIDDEN = 403
        private const val INTERNAL_SERVER_ERROR = 500
        private const val MAX_CONNECTIONS = 5
        private const val LIFETIME_LIMIT_SECONDS = 10L
        private val AWAIT: Duration = Duration.ofSeconds(10)
    }
}
