package com.ogu.raid

import com.ogu.TestcontainersConfiguration
import com.ogu.raid.application.RaidAttackService
import com.ogu.support.MemberFixture
import com.ogu.support.RaidFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration

/** T016: 레이드 상태가 실시간 스트림으로 온다(006 US2, research R6, R7). 실제 HTTP로 스트림에 붙는다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class RaidStreamTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @LocalServerPort
    var port: Int = 0

    private lateinit var members: MemberFixture
    private lateinit var http: SafetyFixture
    private lateinit var tickets: StreamTickets
    private lateinit var raid: RaidFixture
    private val streams = mutableListOf<SseStream>()

    @BeforeEach
    fun setUp() {
        val mockMvc: MockMvc =
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        http = SafetyFixture(mockMvc, jdbcTemplate)
        tickets = StreamTickets(mockMvc)
        raid = RaidFixture(jdbcTemplate, redisTemplate)
    }

    @AfterEach
    fun tearDown() {
        streams.forEach(SseStream::close)
    }

    @Test
    fun `US2-AC1 다른 회원의 공격이 1초 안에 raid 이벤트로 오고 US2-AC4 참여자 수가 실린다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val watcher = members.onboarded()
        val attacker = members.onboarded()
        val stream = open(watcher, topics = "raid")
        // US2-AC5: 붙으면 지금 값을 한 번 받는다
        val first = stream.awaitRaid { it.get("bossId").asLong() == bossId }
        assertThat(first.json().get("hp").asInt()).isEqualTo(30)
        assertThat(first.json().get("participantCount").asInt()).isZero()

        attack(attacker, bossId)

        val event = stream.awaitRaid(limit = Duration.ofSeconds(1)) { it.get("hp").asInt() == 29 }
        val body = event.json()
        assertThat(body.get("bossId").asLong()).isEqualTo(bossId)
        assertThat(body.get("maxHp").asInt()).isEqualTo(30)
        assertThat(body.get("status").asString()).isEqualTo("ALIVE")
        assertThat(body.get("participantCount").asInt()).isEqualTo(1)
        assertThat(body.get("available").asBoolean()).isTrue()
        assertThat(body.has("epoch")).isTrue()
        // id가 없어 알림의 마지막 번호를 바꾸지 않는다
        assertThat(event.id).isNull()
        // 회원의 정보는 싣지 않는다(US4-AC4)
        assertThat(event.data).doesNotContain("myDamage", "memberId", "nickname", "damage")
        assertThat(body.propertyNames().toList())
            .containsExactlyInAnyOrder("bossId", "hp", "maxHp", "status", "participantCount", "available", "epoch")
    }

    @Test
    fun `US2-AC3 공격이 몰려도 1초에 받는 이벤트는 넷 이하이고 마지막 값이 정확하다`() {
        val bossId = raid.freshBoss(maxHp = 500)
        val stream = open(members.onboarded(), topics = "raid")
        stream.awaitRaid { it.get("bossId").asLong() == bossId }
        val attackers = raid.memberIds(ATTACKS)
        val before = stream.raidEvents().size
        val started = System.nanoTime()

        // 공격 사이를 조금 띄워 1초 넘게 이어지게 한다
        attackers.forEach { memberId ->
            directAttack(bossId, memberId)
            Thread.sleep(ATTACK_GAP_MILLIS)
        }

        stream.awaitRaid { it.get("hp").asInt() == 500 - ATTACKS }
        val seconds = Duration.ofNanos(System.nanoTime() - started).toMillis() / MILLIS_PER_SECOND
        val received = stream.raidEvents().size - before
        // 주기가 250ms라 걸린 시간 동안 초당 넷, 여유로 둘을 더 본다
        assertThat(received).isLessThanOrEqualTo(((seconds + 1) * PER_SECOND).toInt() + 2)
        assertThat(received).isLessThan(ATTACKS)
        val last = stream.raidEvents().last().json()
        assertThat(last.get("hp").asInt()).isEqualTo(500 - ATTACKS)
        assertThat(last.get("participantCount").asInt()).isEqualTo(ATTACKS)
        // HP는 줄어들기만 한다(US2-AC2)
        val hps =
            stream
                .raidEvents()
                .map { it.json() }
                .filter { it.get("bossId").asLong() == bossId }
                .map { it.get("hp").asInt() }
        assertThat(hps).isSortedAccordingTo(reverseOrder())
    }

    @Test
    fun `US2-AC5 다시 붙으면 끊긴 동안의 변화를 따라잡은 지금 값을 받는다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val watcher = members.onboarded()
        val first = open(watcher, topics = "raid")
        first.awaitRaid { it.get("hp").asInt() == 30 && it.get("bossId").asLong() == bossId }
        first.close()
        attack(members.onboarded(), bossId)
        attack(members.onboarded(), bossId)

        val second = open(watcher, topics = "raid")

        val event = second.awaitRaid { it.get("bossId").asLong() == bossId }
        assertThat(event.json().get("hp").asInt()).isEqualTo(28)
        assertThat(event.json().get("participantCount").asInt()).isEqualTo(2)
    }

    @Test
    fun `US2-AC7 처치되면 status DEFEATED 이벤트가 온다`() {
        val bossId = raid.freshBoss(maxHp = 2)
        val stream = open(members.onboarded(), topics = "raid")
        stream.awaitRaid { it.get("bossId").asLong() == bossId }

        attack(members.onboarded(), bossId)
        attack(members.onboarded(), bossId)

        val event = stream.awaitRaid { it.get("status").asString() == "DEFEATED" }
        assertThat(event.json().get("hp").asInt()).isZero()
        assertThat(event.json().get("bossId").asLong()).isEqualTo(bossId)
    }

    @Test
    fun `주제를 고르지 않았거나 모르는 주제만 고른 연결은 raid 이벤트를 받지 않는다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val plain = open(members.onboarded(), topics = null)
        val unknown = open(members.onboarded(), topics = "weather,RAID,ra id")
        val listening = open(members.onboarded(), topics = "weather, raid")
        listening.awaitRaid { it.get("bossId").asLong() == bossId }

        attack(members.onboarded(), bossId)

        listening.awaitRaid { it.get("hp").asInt() == 29 }
        assertThat(plain.raidEvents()).isEmpty()
        assertThat(unknown.raidEvents()).isEmpty()
        assertThat(plain.status).isEqualTo(OK)
        assertThat(unknown.status).isEqualTo(OK)
    }

    private fun open(
        member: TestMember,
        topics: String?,
    ): SseStream = SseTestClient.connect(port, tickets.issue(member), topics = topics).also { streams += it }

    private fun attack(
        member: TestMember,
        bossId: Long,
    ) {
        http
            .send(member, HttpMethod.POST, "/api/v1/raid/attacks", mapOf("bossId" to bossId))
            .andExpect(status().isOk)
    }

    /** 회원을 만들지 않고 서비스로 바로 공격한다. 몰리는 공격을 빠르게 만든다. */
    private fun directAttack(
        bossId: Long,
        memberId: Long,
    ) {
        context.getBean(RaidAttackService::class.java).attack(memberId, bossId)
    }

    private companion object {
        const val ATTACKS = 60
        const val ATTACK_GAP_MILLIS = 25L
        const val PER_SECOND = 4
        const val MILLIS_PER_SECOND = 1000.0
        const val OK = 200
    }
}
