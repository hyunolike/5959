package com.ogu.raid

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.raid.application.RaidAttackService
import com.ogu.raid.application.RaidFlusher
import com.ogu.raid.application.RaidQueryService
import com.ogu.raid.domain.AttackOutcome
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidContributionRepository
import com.ogu.raid.domain.RaidLiveStore
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.support.AppInstance
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.QueryCounter
import com.ogu.support.RaidFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant

/**
 * T012, 018 T029: 옮기기, 정상 종료, 다시 올리기, 기록과 맞추기(018 US3-AC5, AC8, research R5, R6).
 *
 * 이 컨텍스트는 주기 옮기기를 1시간으로 늘려 테스트가 [RaidFlusher.flush]를 부를 때만 기록에 적히게 한다. 맞추기는 짧게
 * 자주 돌린다. 보스의 생애 주기 작업은 끈다.
 */
@SpringBootTest(
    properties = [
        "ogu.raid.flush-interval=1h",
        "ogu.raid.reconcile-interval=200ms",
        "ogu.raid.reconcile-window=1h",
        "ogu.raid.lifecycle-scheduler-enabled=false",
    ],
)
@Import(TestcontainersConfiguration::class)
class RaidDurabilityTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var postgres: PostgreSQLContainer

    @Autowired
    lateinit var store: RaidLiveStore

    @Autowired
    lateinit var attackService: RaidAttackService

    @Autowired
    lateinit var queryService: RaidQueryService

    @Autowired
    lateinit var flusher: RaidFlusher

    @Autowired
    lateinit var contributions: RaidContributionRepository

    @Autowired
    lateinit var monsterApi: MonsterApi

    private lateinit var raid: RaidFixture
    private lateinit var members: MemberFixture
    private lateinit var http: SafetyFixture
    private lateinit var coreLoop: CoreLoopFixture

    @BeforeEach
    fun setUp() {
        raid = RaidFixture(jdbcTemplate, store)
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        http = SafetyFixture(mockMvc, jdbcTemplate)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
    }

    @Test
    fun `US3-AC5 정상 종료하면 받아들인 공격이 하나도 사라지지 않는다`() {
        val attackers = raid.memberIds(3)
        val bossId =
            AppInstance.start(oldInstance()).use { old ->
                val bossId = RaidFixture(jdbcTemplate, old.bean()).freshBoss(maxHp = 30)
                attackers.forEach { old.bean<RaidAttackService>().attack(it, bossId) }
                // 주기 옮기기는 1시간 뒤라 아직 기록에 없다. 남은 것은 종료 단계가 적는다
                assertThat(raid.recordedDamageSum(bossId)).isZero()
                bossId
            }

        assertThat(raid.recordedDamageSum(bossId)).isEqualTo(3)
        assertThat(raid.boss(bossId)).containsEntry("hp", 27).containsEntry("participant_count", 3)
        // 다시 뜬 서버(이 컨텍스트)가 같은 값에서 이어 가고 공격을 받는다
        store.clear()
        val state = queryService.state(attackers[0])
        assertThat(state.boss!!.hp).isEqualTo(27)
        assertThat(state.boss!!.participantCount).isEqualTo(3)
        assertThat(state.myDamage).isEqualTo(1)
        assertThat(attackService.attack(attackers[0], bossId).hp).isEqualTo(26)
    }

    @Test
    fun `US3-AC8 쓰기 전에 죽으면 직전 1초만 잃고 숫자가 서로 맞는다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val (first, second) = raid.memberIds(2)
        attackService.attack(first, bossId)
        flusher.flush()
        // 다음 옮기기(1초 뒤) 전에 프로세스가 죽는다
        attackService.attack(second, bossId)
        val before = queryService.state(first).epoch
        Thread.sleep(2)

        store.clear()

        val state = queryService.state(second)
        assertThat(state.boss!!.hp).isEqualTo(29)
        assertThat(state.boss!!.participantCount).isEqualTo(1)
        assertThat(state.myDamage).isZero()
        assertThat(state.epoch).isGreaterThan(before)
        // 사라진 공격은 HP와 기여 양쪽에서 함께 빠진다
        assertThat(raid.liveDamageSum(bossId)).isEqualTo(30 - raid.liveHp())
        assertThat(attackService.attack(second, bossId).hp).isEqualTo(28)
    }

    @Test
    fun `기동 뒤 맞추기가 옛 인스턴스의 마지막 기록을 받아들인다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val (mine, theirs) = raid.memberIds(2)
        attackService.attack(mine, bossId)

        // 배포가 겹친 동안 옛 인스턴스가 받은 공격을 끝나며 적었다
        contributions.upsertAll(bossId, mapOf(mine to 3, theirs to 2), Instant.now())
        jdbcTemplate.update("update raid_boss set hp = 25, participant_count = 2 where id = ?", bossId)

        await().atMost(Duration.ofSeconds(5)).until { queryService.state(mine).myDamage == 3 }
        val state = queryService.state(theirs)
        assertThat(state.boss!!.hp).isEqualTo(25)
        assertThat(state.boss!!.participantCount).isEqualTo(2)
        assertThat(state.myDamage).isEqualTo(2)
        flusher.flush()
        assertThat(raid.recordedDamageSum(bossId)).isEqualTo(5)
        assertThat(raid.boss(bossId)).containsEntry("hp", 25)
    }

    @Test
    fun `종료 중에는 공격이 503 RAID_UNAVAILABLE이고 조회는 마지막 기록과 available false다`() {
        val member = members.onboarded()
        val late = members.onboarded()
        val bossId = raid.freshBoss(maxHp = 30)
        attack(member, bossId).andExpect(status().isOk)

        try {
            flusher.stop()

            // 종료 단계가 받아들인 공격을 이미 적었다
            assertThat(raid.recordedDamage(bossId, member.id)).isEqualTo(1)
            attack(late, bossId)
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.error.code").value("RAID_UNAVAILABLE"))
            http
                .get(member, "/api/v1/raid")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.boss.hp").value(29))
                .andExpect(jsonPath("$.data.myDamage").value(1))
            assertThat(raid.recordedDamage(bossId, late.id)).isZero()
        } finally {
            flusher.start()
        }
    }

    @Test
    fun `FR-015 레이드가 공격을 받지 않는 동안에도 글쓰기, 댓글, 알림은 된다`() {
        val author = members.onboarded()
        val other = members.onboarded()

        try {
            flusher.stop()

            val postId = coreLoop.createPost(author, "[실패] 레이드가 멈춰도 쓰는 글")
            coreLoop.comment(other, postId, "댓글도 달려요")
            coreLoop.likePost(other, postId).andExpect(status().is2xxSuccessful)
            http.get(author, "/api/v1/notifications").andExpect(status().isOk)
            http.get(other, "/api/v1/feed").andExpect(status().isOk)
            http.awaitNotifications(author, "POST_COMMENT", 1)
        } finally {
            flusher.start()
        }
    }

    @Test
    fun `처치된 보스는 다시 올려도 처치된 채이고 공격을 받지 않는다`() {
        val bossId = raid.freshBoss(maxHp = 1)
        val (first, second) = raid.memberIds(2)
        assertThat(attackService.attack(first, bossId).defeated).isTrue()

        store.clear()

        assertThatThrownBy { attackService.attack(second, bossId) }
            .isInstanceOfSatisfying(BusinessException::class.java) {
                assertThat(it.errorCode).isEqualTo(ErrorCode.RAID_BOSS_ENDED)
            }
        val state = queryService.state(first)
        assertThat(state.boss!!.status).isEqualTo(RaidBossStatus.DEFEATED)
        assertThat(state.boss!!.hp).isZero()
        assertThat(state.myDamage).isEqualTo(1)
        assertThat(state.nextBossAt).isNotNull()
    }

    @Test
    fun `같은 값을 두 번 적거나 더 작은 값이 늦게 와도 기록은 그대로다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val (member) = raid.memberIds(1)
        val now = Instant.now()

        contributions.upsertAll(bossId, mapOf(member to 3), now)
        contributions.upsertAll(bossId, mapOf(member to 3), now)
        contributions.upsertAll(bossId, mapOf(member to 2), now)

        assertThat(raid.recordedDamage(bossId, member)).isEqualTo(3)
        contributions.upsertAll(bossId, mapOf(member to 5), now)
        assertThat(raid.recordedDamage(bossId, member)).isEqualTo(5)
    }

    @Test
    fun `처치를 기록에 남기지 못했으면 다음 옮기기가 마무리한다`() {
        val bossId = raid.freshBoss(maxHp = 2)
        val (first, second) = raid.memberIds(2)
        queryService.current()
        // 서비스를 거치지 않고 저장소로만 공격한다. 메모리의 보스는 처치됐지만 기록에 남기는 일은 일어나지 않았다.
        // 처치 직후 기록에 남기다 실패한 것과 같은 상태다
        store.attack(bossId, first, COOLDOWN, Instant.now())
        val last = store.attack(bossId, second, COOLDOWN, Instant.now())
        assertThat((last as AttackOutcome.Accepted).defeated).isTrue()

        flusher.flush()

        raid.awaitStatus(bossId, "DEFEATED")
        assertThat(raid.boss(bossId)).containsEntry("hp", 0).containsEntry("participant_count", 2)
        assertThat(raid.recordedDamageSum(bossId)).isEqualTo(2)
        assertThat(raid.boss(bossId)["ended_at"]).isNotNull()
    }

    @Test
    fun `바뀐 회원이 없으면 옮기기는 DB에 쿼리를 내지 않는다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val (member) = raid.memberIds(1)
        attackService.attack(member, bossId)
        flusher.flush()
        assertThat(raid.recordedDamage(bossId, member)).isEqualTo(1)

        // 018 T024: 살아 있는 보스라도 바뀐 회원이 없으면 매초 UPDATE를 내지 않는다
        val (_, queries) = QueryCounter.count { flusher.flush() }

        assertThat(queries).isZero()
    }

    private fun attack(
        member: TestMember,
        bossId: Long,
    ) = http.send(member, HttpMethod.POST, "/api/v1/raid/attacks", mapOf("bossId" to bossId))

    /** 같은 DB에 붙는 옛 서버. 주기 옮기기를 늦춰 종료 단계만 기록에 적게 한다. Redis는 레이드와 상관없다. */
    private fun oldInstance(): Map<String, String> =
        mapOf(
            "spring.datasource.url" to postgres.jdbcUrl,
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to postgres.password,
            "spring.data.redis.url" to "redis://localhost:1",
            "ogu.raid.flush-interval" to "1h",
            "ogu.raid.lifecycle-scheduler-enabled" to "false",
        )

    private companion object {
        val COOLDOWN: Duration = Duration.ofSeconds(1)
    }
}
