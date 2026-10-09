package com.ogu.raid

import com.ogu.TestcontainersConfiguration
import com.ogu.raid.application.RaidFinisher
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.support.MemberFixture
import com.ogu.support.RaidFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** T021: 보스를 물리치면 함께한 회원 모두가 알림을 받는다(006 US4, research R11, R12). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RaidNotificationTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var finisher: RaidFinisher

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var transaction: TransactionTemplate

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var http: SafetyFixture
    private lateinit var raid: RaidFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        http = SafetyFixture(mockMvc, jdbcTemplate)
        raid = RaidFixture(jdbcTemplate, redisTemplate)
    }

    @Test
    fun `US4-AC1 처치되면 참여한 회원마다 알림이 하나이고 US4-AC2 공격한 적 없는 회원에게는 없다`() {
        val bossId = raid.freshBoss(maxHp = 3)
        val first = members.onboarded()
        val second = members.onboarded()
        val bystander = members.onboarded()
        http.get(bystander, RAID).andExpect(status().isOk)

        attack(first, bossId)
        attack(second, bossId)
        raid.clearCooldown(first.id)
        attack(first, bossId)

        http.awaitNotifications(first, TYPE, 1)
        http.awaitNotifications(second, TYPE, 1)
        http.awaitListenersIdle()
        assertThat(http.notificationKeys(first, TYPE)).containsExactly("RAID:$bossId")
        assertThat(http.notificationKeys(bystander, TYPE)).isEmpty()
        // 알림에는 글이 없고 다른 회원의 정보도 없다(US4-AC4)
        val item =
            http
                .data(first, "/api/v1/notifications")
                .get("items")
                .values()
                .single { it.get("type").asString() == TYPE }
        assertThat(item.get("postId").isNull).isTrue()
        assertThat(item.get("post").isNull).isTrue()
        assertThat(item.get("actor").isNull).isTrue()
        assertThat(item.get("actorCount").asInt()).isEqualTo(1)
        assertThat(item.get("read").asBoolean()).isFalse()
    }

    @Test
    fun `US4-AC5 감정 통계의 raidBossesDefeated가 처치한 보스마다 1 는다`() {
        val member = members.onboarded()
        val bystander = members.onboarded()
        val before = defeatedCount(member)

        val bossId = raid.freshBoss(maxHp = 1)
        attack(member, bossId)
        raid.awaitStatus(bossId, "DEFEATED")

        assertThat(defeatedCount(member)).isEqualTo(before + 1)
        assertThat(defeatedCount(bystander)).isZero()
    }

    @Test
    fun `US4-AC7 물러난 보스는 알리지 않고 함께 물리친 수도 그대로다`() {
        val bossId = raid.freshBoss(maxHp = 10)
        val member = members.onboarded()
        attack(member, bossId)

        finisher.retreat(bossId, Instant.now())

        raid.awaitStatus(bossId, "RETREATED")
        http.awaitListenersIdle()
        assertThat(http.notificationKeys(member, TYPE)).isEmpty()
        assertThat(defeatedCount(member)).isZero()
        assertThat(raid.boss(bossId)).containsEntry("hp", 9).containsEntry("participant_count", 1)
    }

    @Test
    fun `US3-AC3 마지막 HP를 다퉈도 처치 알림은 회원마다 하나이고, 이벤트가 다시 와도 늘지 않는다`() {
        val bossId = raid.freshBoss(maxHp = 4)
        val attackers = (1..ATTACKERS).map { members.onboarded() }
        http.get(attackers[0], RAID).andExpect(status().isOk)
        val pool = Executors.newFixedThreadPool(ATTACKERS)
        val start = CountDownLatch(1)

        val statuses =
            try {
                attackers
                    .map { member ->
                        pool.submit(
                            Callable {
                                start.await()
                                http
                                    .send(member, HttpMethod.POST, ATTACKS, mapOf("bossId" to bossId))
                                    .andReturn()
                                    .response.status
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertThat(statuses.count { it == OK }).isEqualTo(4)
        assertThat(statuses.count { it == CONFLICT }).isEqualTo(ATTACKERS - 4)
        raid.awaitStatus(bossId, "DEFEATED")
        val participants = attackers.filter { raid.recordedDamage(bossId, it.id) > 0 }
        assertThat(participants).hasSize(4)
        participants.forEach { http.awaitNotifications(it, TYPE, 1) }

        // 같은 이벤트가 다시 전달돼도 알림은 그대로다
        transaction.executeWithoutResult { events.publishEvent(RaidBossDefeated(bossId)) }
        http.awaitListenersIdle()
        assertThat(notificationCount(bossId)).isEqualTo(4)
        assertThat(finisherStatus(bossId)).isEqualTo(RaidBossStatus.DEFEATED.name)
    }

    private fun attack(
        member: TestMember,
        bossId: Long,
    ) {
        http.send(member, HttpMethod.POST, ATTACKS, mapOf("bossId" to bossId)).andExpect(status().isOk)
    }

    private fun defeatedCount(member: TestMember): Int =
        http.data(member, "/api/v1/members/me/emotion-stats").get("raidBossesDefeated").asInt()

    private fun notificationCount(bossId: Long): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from notification where raid_boss_id = ?",
            Int::class.java,
            bossId,
        )!!

    private fun finisherStatus(bossId: Long): String = raid.boss(bossId)["status"] as String

    private companion object {
        const val RAID = "/api/v1/raid"
        const val ATTACKS = "/api/v1/raid/attacks"
        const val TYPE = "RAID_BOSS_DEFEATED"
        const val ATTACKERS = 10
        const val OK = 200
        const val CONFLICT = 409
    }
}
