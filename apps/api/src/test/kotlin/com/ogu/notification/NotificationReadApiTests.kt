package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.notification.application.NotificationReadService
import com.ogu.notification.application.NotificationWriter
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.http.MediaType
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * T039: 읽음 처리 `PUT /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/read-all`(US2-AC3, AC4, AC6,
 * FR-008, FR-009, research R7, R11). 읽음이 바뀌면 그 회원의 열린 연결이 `unread-count` 이벤트를 받는지는 실제 스트림으로
 * 본다. 읽음과 공감이 겹치는 경우는 한쪽 트랜잭션을 커밋 전에 붙잡아 두고, 다른 쪽이 행 잠금을 기다리는 것을
 * `pg_stat_activity`로 확인한 뒤 풀어 준다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
// 이 클래스만의 설정이라 다른 테스트와 컨텍스트를 나누지 않는다. 끝나면 닫아 컨테이너와 메모리를 돌려준다
@DirtiesContext
// 안전망이나 하트비트가 끼어들지 않게 길게 둔다. unread-count는 Redis 신호로만 올 수 있다
@TestPropertySource(properties = ["ogu.notification.safety-drain-interval=10m"])
class NotificationReadApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var redis: StringRedisTemplate

    @Autowired
    lateinit var container: RedisMessageListenerContainer

    @Autowired
    lateinit var readService: NotificationReadService

    @Autowired
    lateinit var writer: NotificationWriter

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @LocalServerPort
    var port: Int = 0

    private val unusedIds = AtomicLong(System.nanoTime() % UNUSED_ID_RANGE)
    private val executor = Executors.newFixedThreadPool(2)
    private val streams = mutableListOf<SseStream>()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var tickets: StreamTickets
    private lateinit var support: NotificationTestSupport

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        tickets = StreamTickets(mockMvc)
        support = NotificationTestSupport(jdbcTemplate, redis, container)
    }

    @AfterEach
    fun tearDown() {
        streams.forEach(SseStream::close)
        executor.shutdownNow()
    }

    @Test
    fun `US2-AC3 하나 읽으면 204이고 안 읽은 수가 하나 준다`() {
        val member = members.onboarded()
        val ids = (1L..3L).map { support.seed(member.id, unusedId(), seq = it) }
        assertThat(unreadCount(member)).isEqualTo(3)

        markOne(member, ids[1]).andExpect(status().isNoContent).andExpect(content().string(""))

        assertThat(unreadCount(member)).isEqualTo(2)
        assertThat(support.notificationsOf(member.id).map { it.read }).containsExactly(false, true, false)
        // 이미 읽었으면 그대로 204이고 수도 그대로다
        markOne(member, ids[1]).andExpect(status().isNoContent)
        assertThat(unreadCount(member)).isEqualTo(2)
    }

    @Test
    fun `US2-AC4 모두 읽음은 upToSeq 이하만 읽음으로 바꾸고 그 뒤 번호는 안 읽은 채 남긴다`() {
        val member = members.onboarded()
        val other = members.onboarded()
        val ids = (1L..5L).map { support.seed(member.id, unusedId(), seq = it) }
        support.markRead(ids[1])
        support.seed(other.id, unusedId(), seq = 1)

        markAll(member, upToSeq = 3)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.updated").value(2))
            .andExpect(jsonPath("$.data.unreadCount").value(2))

        // 누르는 사이에 온 알림(4, 5)은 번호가 더 커서 안 읽은 채로 남는다
        assertThat(support.notificationsOf(member.id).map { it.read }).containsExactly(true, true, true, false, false)
        assertThat(unreadCount(member)).isEqualTo(2)
        assertThat(support.notificationsOf(other.id).single().read).isFalse()
        // 다시 눌러도 바뀌는 것이 없다
        markAll(member, upToSeq = 3)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updated").value(0))
            .andExpect(jsonPath("$.data.unreadCount").value(2))
        markAll(member, upToSeq = 0).andExpect(jsonPath("$.data.updated").value(0))
    }

    @Test
    fun `US2-AC6 다른 회원의 알림을 읽음 처리하면 404 NOTIFICATION_NOT_FOUND이고 그 알림은 그대로 안 읽음`() {
        val owner = members.onboarded()
        val stranger = members.onboarded()
        val id = support.seed(owner.id, unusedId(), seq = 1)

        val body =
            markOne(stranger, id)
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"))
                .andReturn()
                .response.contentAsString

        assertThat(support.notificationsOf(owner.id).single().read).isFalse()
        assertThat(unreadCount(owner)).isEqualTo(1)
        // 없는 알림과 응답이 똑같아 존재 여부가 드러나지 않는다
        val missing =
            markOne(stranger, MISSING_ID)
                .andExpect(status().isNotFound)
                .andReturn()
                .response.contentAsString
        assertThat(body).isEqualTo(missing)
    }

    @Test
    fun `90일이 지난 알림 읽음은 404이고 모두 읽음도 그 알림을 건드리지 않는다`() {
        val member = members.onboarded()
        val expired = Instant.now().minus(RETENTION).minusSeconds(1)
        val old = support.backdate(support.seed(member.id, unusedId(), seq = 1), expired)
        support.seed(member.id, unusedId(), seq = 2)

        markOne(member, old)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"))
        markAll(member, upToSeq = 2)
            .andExpect(jsonPath("$.data.updated").value(1))
            .andExpect(jsonPath("$.data.unreadCount").value(0))

        assertThat(support.notificationsOf(member.id).map { it.read }).containsExactly(false, true)
    }

    @Test
    fun `읽음이 바뀌면 그 회원의 열린 연결 모두에 unread-count 이벤트(ID 없음)가 간다`() {
        val member = members.onboarded()
        val other = members.onboarded()
        val ids = (1L..3L).map { support.seed(member.id, unusedId(), seq = it) }
        support.seed(other.id, unusedId(), seq = 1)
        val tabs = listOf(open(member), open(member))
        val othersTab = open(other)

        markOne(member, ids.first()).andExpect(status().isNoContent)

        tabs.forEach { tab ->
            await().atMost(AWAIT).pollInterval(POLL).until { unreadCounts(tab) == listOf(2L) }
            val event = tab.events.single { it.event == UNREAD_COUNT }
            assertThat(event.id).isNull()
            assertThat(event.json().propertyNames()).containsExactly("unreadCount")
        }
        // 바뀐 것이 없는 읽음(이미 읽은 알림)은 이벤트를 보내지 않는다. 뒤이은 모두 읽음의 이벤트만 온다
        markOne(member, ids.first()).andExpect(status().isNoContent)
        markAll(member, upToSeq = 3).andExpect(status().isOk)
        tabs.forEach { tab ->
            await().atMost(AWAIT).pollInterval(POLL).until { unreadCounts(tab).size >= 2 }
            assertThat(unreadCounts(tab)).containsExactly(2L, 0L)
            assertThat(tab.notifications()).isEmpty()
        }
        // 다른 회원의 연결에는 가지 않는다
        assertThat(unreadCounts(othersTab)).isEmpty()
    }

    @Test
    fun `읽음이 먼저 커밋되면 뒤따른 같은 글 공감은 새 묶음을 만든다`() {
        val author = members.onboarded()
        val postId = unusedId()
        transactionTemplate.executeWithoutResult { writer.addLike(author.id, postId, unusedId()) }
        val group = support.notificationsOf(author.id).single()
        val marked = CountDownLatch(1)
        val release = CountDownLatch(1)

        val reader =
            executor.submit<Int> {
                transactionTemplate.execute {
                    val result = readService.markAll(author.id, group.seq)
                    marked.countDown()
                    release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    result.updated
                }
            }
        assertThat(marked.await(HOLD_SECONDS, TimeUnit.SECONDS)).isTrue()
        val liker =
            executor.submit<Boolean> {
                transactionTemplate.execute { writer.addLike(author.id, postId, unusedId()) }
            }
        // 공감은 읽음이 잡은 묶음 행 잠금을 기다린다
        awaitBlocked(LIKE_UPSERT)
        release.countDown()

        assertThat(reader.get(HOLD_SECONDS, TimeUnit.SECONDS)).isEqualTo(1)
        assertThat(liker.get(HOLD_SECONDS, TimeUnit.SECONDS)).isTrue()
        val groups = support.notificationsOf(author.id, "POST_LIKE")
        assertThat(groups).hasSize(2)
        assertThat(groups[0].id).isEqualTo(group.id)
        assertThat(groups[0].read).isTrue()
        assertThat(groups[0].actorCount).isEqualTo(1)
        assertThat(groups[1].read).isFalse()
        assertThat(groups[1].actorCount).isEqualTo(1)
        assertThat(groups[1].seq).isGreaterThan(group.seq)
        assertThat(unreadCount(author)).isEqualTo(1)
    }

    @Test
    fun `공감이 먼저 커밋되면 묶음 번호가 upToSeq보다 커져 모두 읽음 뒤에도 안 읽음으로 남는다`() {
        val author = members.onboarded()
        val postId = unusedId()
        transactionTemplate.executeWithoutResult { writer.addLike(author.id, postId, unusedId()) }
        val group = support.notificationsOf(author.id).single()
        val liked = CountDownLatch(1)
        val release = CountDownLatch(1)

        val liker =
            executor.submit<Boolean> {
                transactionTemplate.execute {
                    val added = writer.addLike(author.id, postId, unusedId())
                    liked.countDown()
                    release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    added
                }
            }
        assertThat(liked.await(HOLD_SECONDS, TimeUnit.SECONDS)).isTrue()
        // 화면이 본 번호(갱신 전)로 모두 읽음을 누른다. 공감이 잡은 묶음 행 잠금을 기다린다
        val reader = executor.submit<Int> { readService.markAll(author.id, group.seq).updated }
        awaitBlocked(READ_UPDATE)
        release.countDown()

        assertThat(liker.get(HOLD_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(reader.get(HOLD_SECONDS, TimeUnit.SECONDS)).isZero()
        val bumped = support.notificationsOf(author.id).single()
        assertThat(bumped.id).isEqualTo(group.id)
        assertThat(bumped.read).isFalse()
        assertThat(bumped.actorCount).isEqualTo(2)
        assertThat(bumped.seq).isGreaterThan(group.seq)
        assertThat(unreadCount(author)).isEqualTo(1)
    }

    @Test
    fun `upToSeq가 지금 번호보다 커도 지금 번호까지만 읽어, 누르는 사이에 더해진 공감은 안 읽음으로 남는다`() {
        val author = members.onboarded()
        val postId = unusedId()
        transactionTemplate.executeWithoutResult { writer.addLike(author.id, postId, unusedId()) }
        val group = support.notificationsOf(author.id).single()
        val liked = CountDownLatch(1)
        val release = CountDownLatch(1)

        val liker =
            executor.submit<Boolean> {
                transactionTemplate.execute {
                    val added = writer.addLike(author.id, postId, unusedId())
                    liked.countDown()
                    release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    added
                }
            }
        assertThat(liked.await(HOLD_SECONDS, TimeUnit.SECONDS)).isTrue()
        // 번호를 한참 크게 보내도 이 요청이 시작할 때 커밋돼 있던 마지막 번호로 낮춘다
        val reader = executor.submit<Int> { readService.markAll(author.id, Long.MAX_VALUE).updated }
        awaitBlocked(READ_UPDATE)
        release.countDown()

        assertThat(liker.get(HOLD_SECONDS, TimeUnit.SECONDS)).isTrue()
        assertThat(reader.get(HOLD_SECONDS, TimeUnit.SECONDS)).isZero()
        val bumped = support.notificationsOf(author.id).single()
        assertThat(bumped.read).isFalse()
        assertThat(bumped.actorCount).isEqualTo(2)
        assertThat(bumped.seq).isGreaterThan(group.seq)
        // 겹치는 것이 없으면 큰 번호는 지금 있는 것을 모두 읽는다
        markAll(author, upToSeq = Long.MAX_VALUE)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updated").value(1))
            .andExpect(jsonPath("$.data.unreadCount").value(0))
    }

    @Test
    fun `하나 읽음이 기다리는 사이에 공감이 더해진 묶음은 더해진 공감까지 읽음이 된다`() {
        val author = members.onboarded()
        val postId = unusedId()
        transactionTemplate.executeWithoutResult { writer.addLike(author.id, postId, unusedId()) }
        val group = support.notificationsOf(author.id).single()
        val liked = CountDownLatch(1)
        val release = CountDownLatch(1)

        val liker =
            executor.submit<Boolean> {
                transactionTemplate.execute {
                    val added = writer.addLike(author.id, postId, unusedId())
                    liked.countDown()
                    release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    added
                }
            }
        assertThat(liked.await(HOLD_SECONDS, TimeUnit.SECONDS)).isTrue()
        // 회원이 그 묶음을 눌렀다. 번호가 아니라 알림 ID로 읽으므로 방금 더해진 공감도 함께 읽음이 된다
        val reader = executor.submit { readService.markOne(author.id, group.id) }
        awaitBlocked(READ_UPDATE)
        release.countDown()

        assertThat(liker.get(HOLD_SECONDS, TimeUnit.SECONDS)).isTrue()
        reader.get(HOLD_SECONDS, TimeUnit.SECONDS)
        val read = support.notificationsOf(author.id).single()
        assertThat(read.id).isEqualTo(group.id)
        assertThat(read.read).isTrue()
        assertThat(read.actorCount).isEqualTo(2)
        assertThat(unreadCount(author)).isZero()
    }

    @Test
    fun `upToSeq가 없거나 음수이거나 알림 ID 형식이 틀리면 400 INVALID_REQUEST`() {
        val member = members.onboarded()

        listOf("{}", """{"upToSeq": null}""", """{"upToSeq": -1}""", """{"upToSeq": "many"}""", "").forEach { body ->
            val request = post(READ_ALL_PATH).bearer(member.accessToken).contentType(MediaType.APPLICATION_JSON)
            mockMvc
                .perform(request.content(body))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
        mockMvc
            .perform(put("/api/v1/notifications/abc/read").bearer(member.accessToken))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `인증 없이 부르면 401`() {
        mockMvc.perform(put(READ_PATH, 1L)).andExpect(status().isUnauthorized)
        mockMvc
            .perform(post(READ_ALL_PATH).contentType(MediaType.APPLICATION_JSON).content("""{"upToSeq": 1}"""))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `온보딩 전 회원은 403 ONBOARDING_REQUIRED`() {
        val member = members.signedUp()

        markOne(member, 1L)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        markAll(member, upToSeq = 1)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
    }

    private fun markOne(
        member: TestMember,
        notificationId: Long,
    ): ResultActions = mockMvc.perform(put(READ_PATH, notificationId).bearer(member.accessToken))

    private fun markAll(
        member: TestMember,
        upToSeq: Long,
    ): ResultActions =
        mockMvc.perform(
            post(READ_ALL_PATH)
                .bearer(member.accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"upToSeq": $upToSeq}"""),
        )

    private fun unreadCount(member: TestMember): Long =
        loop
            .data(mockMvc.perform(get("/api/v1/notifications/unread-count").bearer(member.accessToken)))
            .get("count")
            .asLong()

    private fun open(member: TestMember): SseStream =
        SseTestClient.connect(port, tickets.issue(member)).also {
            streams += it
            assertThat(it.status).isEqualTo(OK)
        }

    private fun unreadCounts(stream: SseStream): List<Long> =
        stream.events.filter { it.event == UNREAD_COUNT }.map { it.json().get("unreadCount").asLong() }

    /**
     * [statementPrefix]로 시작하는 문장([LIKE_UPSERT], [READ_UPDATE])이 행 잠금을 기다릴 때까지 기다린다. 통계 스냅숏은
     * 트랜잭션 동안 굳으므로 볼 때마다 같은 연결에서 `pg_stat_clear_snapshot()`을 먼저 부른다.
     */
    private fun awaitBlocked(statementPrefix: String) {
        await().atMost(AWAIT).pollInterval(POLL).until {
            jdbcTemplate.execute(
                ConnectionCallback { connection ->
                    connection.createStatement().use { it.execute("select pg_stat_clear_snapshot()") }
                    connection
                        .prepareStatement(
                            """
                            select count(*) from pg_stat_activity
                            where wait_event_type = 'Lock' and state = 'active' and pid <> pg_backend_pid()
                              and ltrim(query) like ? || '%'
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, statementPrefix)
                            statement.executeQuery().use { rows ->
                                rows.next()
                                rows.getInt(1) > 0
                            }
                        }
                },
            ) == true
        }
    }

    /** 어떤 회원이나 글도 가리키지 않는 ID. 알림 모듈은 회원과 글 표를 직접 보지 않아 이런 값도 저장된다. */
    private fun unusedId(): Long = UNUSED_ID_BASE + unusedIds.incrementAndGet()

    private companion object {
        const val READ_PATH = "/api/v1/notifications/{notificationId}/read"
        const val READ_ALL_PATH = "/api/v1/notifications/read-all"
        const val UNREAD_COUNT = "unread-count"
        const val OK = 200
        const val MISSING_ID = 8_999_999_999_999L
        const val UNUSED_ID_BASE = 9_000_000_000L
        const val UNUSED_ID_RANGE = 1_000_000_000L
        const val HOLD_SECONDS = 20L

        /** 공감 묶음을 만들거나 고치는 문장(`NotificationRepository.upsertLikeGroup`)의 머리. */
        const val LIKE_UPSERT = "insert into notification (receiver_id, type, post_id, latest_actor_id, seq,"

        /** 읽음 처리 문장(`markRead`, `markAllRead`)의 머리. */
        const val READ_UPDATE = "update notification n set read_at ="
        val AWAIT: Duration = Duration.ofSeconds(15)
        val POLL: Duration = Duration.ofMillis(20)
        val RETENTION: Duration = Duration.ofDays(90)
    }
}
