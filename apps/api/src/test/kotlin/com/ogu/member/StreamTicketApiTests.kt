package com.ogu.member

import com.ogu.TestcontainersConfiguration
import com.ogu.member.application.SseTicketCleanupJob
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T021: SSE 연결 표(004 research R3). 발급은 Bearer와 온보딩이 필요하고, 스트림은 표로만 붙는다. 표는 해시로만 저장하고
 * 30초 안에 한 번만 쓸 수 있으며, 발급한 세션이 끝나면 쓸 수 없다. 시계는 [MutableClock]으로 움직인다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class, StreamTicketApiTests.ClockOverride::class)
class StreamTicketApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var cleanupJob: SseTicketCleanupJob

    @LocalServerPort
    var port: Int = 0

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var tickets: StreamTickets
    private val streams = mutableListOf<SseStream>()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        tickets = StreamTickets(mockMvc)
    }

    @AfterEach
    fun tearDown() {
        streams.forEach(SseStream::close)
    }

    @Test
    fun `티켓 발급은 201과 StreamTicket이고 30초 뒤 만료된다`() {
        val member = members.onboarded()
        val before = clock.instant()

        tickets
            .request(member)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.ticket").value(matchesPattern("^[A-Za-z0-9_-]{43}$")))
            .andExpect(jsonPath("$.data.expiresAt").value(before.plusSeconds(TTL_SECONDS).toString()))
            .andExpect(jsonPath("$.error").doesNotExist())
    }

    @Test
    fun `US1-AC8 로그인하지 않으면 티켓 발급은 401`() {
        mockMvc
            .perform(post(StreamTickets.PATH))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
    }

    @Test
    fun `온보딩 전 회원은 티켓 발급이 403 ONBOARDING_REQUIRED`() {
        val member = members.signedUp()

        tickets
            .request(member)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
    }

    @Test
    fun `US1-AC8 티켓 없이, 이미 쓴 티켓, 30초가 지난 티켓(시계 주입 경계), 세션이 끝난 티켓으로는 스트림이 401 STREAM_TICKET_INVALID`() {
        val member = members.onboarded()

        // 티켓 없이, 모르는 티켓
        assertInvalid(connect(null))
        assertInvalid(connect("not-a-ticket"))

        // 이미 쓴 티켓
        val used = tickets.issue(member)
        assertThat(connect(used).status).isEqualTo(OK)
        assertInvalid(connect(used))

        // 만료 경계: 30초가 되기 직전에는 붙고, 정확히 30초가 되면 못 붙는다
        val almost = tickets.issue(member)
        val expired = tickets.issue(member)
        clock.advance(Duration.ofSeconds(TTL_SECONDS).minusNanos(MICRO))
        assertThat(connect(almost).status).isEqualTo(OK)
        clock.advance(Duration.ofNanos(MICRO))
        assertInvalid(connect(expired))

        // 발급한 세션이 끝난 티켓(로그아웃)
        val afterLogout = tickets.issue(member)
        logout(member)
        assertInvalid(connect(afterLogout))
    }

    @Test
    fun `DB에는 티켓의 SHA-256 해시만 저장한다`() {
        val member = members.onboarded()
        val ticket = tickets.issue(member)

        val rows =
            jdbcTemplate.queryForList("select token_hash, member_id from sse_ticket where member_id = ?", member.id)
        assertThat(rows).hasSize(1)
        assertThat(rows.single()["token_hash"]).isEqualTo(sha256(ticket))
        val rawStored =
            jdbcTemplate.queryForObject(
                "select count(*) from sse_ticket where position(? in sse_ticket::text) > 0",
                Int::class.java,
                ticket,
            )
        assertThat(rawStored).isZero()
    }

    @Test
    fun `같은 티켓으로 동시에 여러 번 붙으면 한 번만 성공한다`() {
        val member = members.onboarded()
        val ticket = tickets.issue(member)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS)
        try {
            val futures =
                (1..CONCURRENT_ATTEMPTS).map {
                    pool.submit<SseStream> {
                        start.await()
                        connect(ticket)
                    }
                }
            start.countDown()
            val statuses = futures.map { it.get(AWAIT_SECONDS, TimeUnit.SECONDS).status }

            assertThat(statuses.count { it == OK }).isEqualTo(1)
            assertThat(statuses.count { it == UNAUTHORIZED }).isEqualTo(CONCURRENT_ATTEMPTS - 1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `SseTicketCleanupJob은 만료된 지 하루가 지난 행만 지운다`() {
        val member = members.onboarded()
        val now = clock.instant()
        val dayAgo = now.minus(Duration.ofDays(1))
        insertTicket("stale", member.id, expiresAt = dayAgo.minusNanos(MICRO))
        insertTicket("boundary", member.id, expiresAt = dayAgo)
        insertTicket("recent", member.id, expiresAt = dayAgo.plusNanos(MICRO))
        insertTicket("live", member.id, expiresAt = now.plusSeconds(TTL_SECONDS))

        cleanupJob.deleteStaleTickets()

        val remaining =
            jdbcTemplate.queryForList(
                "select token_hash from sse_ticket where member_id = ?",
                String::class.java,
                member.id,
            )
        assertThat(remaining).containsExactlyInAnyOrder(sha256("boundary"), sha256("recent"), sha256("live"))
    }

    private fun insertTicket(
        raw: String,
        memberId: Long,
        expiresAt: Instant,
    ) {
        jdbcTemplate.update(
            """
            insert into sse_ticket (token_hash, member_id, session_id, created_at, expires_at)
            values (?, ?, gen_random_uuid(), ?, ?)
            """.trimIndent(),
            sha256(raw),
            memberId,
            Timestamp.from(expiresAt.minusSeconds(TTL_SECONDS)),
            Timestamp.from(expiresAt),
        )
    }

    private fun connect(ticket: String?): SseStream = SseTestClient.connect(port, ticket).also { streams += it }

    private fun assertInvalid(stream: SseStream) {
        assertThat(stream.status).isEqualTo(UNAUTHORIZED)
        val body = stream.errorBody()
        assertThat(body.get("success").asBoolean()).isFalse()
        assertThat(body.get("error").get("code").asString()).isEqualTo("STREAM_TICKET_INVALID")
    }

    private fun logout(member: TestMember) {
        mockMvc.perform(post("/api/v1/auth/logout").bearer(member.accessToken)).andExpect(status().isNoContent)
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    @TestConfiguration(proxyBeanMethods = false)
    class ClockOverride {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS))
    }

    private companion object {
        const val TTL_SECONDS = 30L
        const val MICRO = 1_000L
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val CONCURRENT_ATTEMPTS = 8
        const val AWAIT_SECONDS = 15L
    }
}
