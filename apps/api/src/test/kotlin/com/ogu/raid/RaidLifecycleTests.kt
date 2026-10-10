package com.ogu.raid

import com.ogu.TestcontainersConfiguration
import com.ogu.post.ContentType
import com.ogu.post.PostApi
import com.ogu.post.PostModerationApi
import com.ogu.raid.application.RaidAttackService
import com.ogu.raid.application.RaidBossLifecycle
import com.ogu.raid.application.RaidQueryService
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.RaidFixture
import com.ogu.support.SafetyFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T024: 보스의 생애(006 US5, research R10). 이 컨텍스트의 시계를 테스트가 움직이고 주기 작업을 직접 부른다.
 * 시계는 앞으로만 가므로 테스트마다 지금 시각을 기준으로 보스를 새로 놓는다. 이 컨텍스트는 테스트가 끝난 뒤에도 남아
 * 있으므로 주기 작업을 꺼 둔다. 켜 두면 앞서간 시계로 다른 테스트의 보스를 물러나게 한다. 다른 컨텍스트의 주기 작업은
 * 실제 시계로 돌고, 실제 오늘보다 뒤에 끝난 보스는 건드리지 않는다.
 */
@SpringBootTest(properties = ["ogu.raid.lifecycle-scheduler-enabled=false"])
@Import(TestcontainersConfiguration::class, RaidLifecycleTests.ClockOverride::class)
class RaidLifecycleTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var lifecycle: RaidBossLifecycle

    @Autowired
    lateinit var queryService: RaidQueryService

    @Autowired
    lateinit var attackService: RaidAttackService

    @Autowired
    lateinit var postApi: PostApi

    @Autowired
    lateinit var moderation: PostModerationApi

    @Autowired
    lateinit var clock: MutableClock

    private lateinit var raid: RaidFixture

    @BeforeEach
    fun setUp() {
        raid = RaidFixture(jdbcTemplate, redisTemplate)
        // 날짜 경계에 걸리지 않게 한국 시간 낮 12시로 맞춘다
        clock.advance(Duration.between(clock.instant(), nextNoon()))
    }

    @Test
    fun `US5-AC1 보스가 한 번도 없으면 HP 300의 첫 보스가 나타난다`() {
        jdbcTemplate.update("delete from raid_contribution")
        jdbcTemplate.update("delete from raid_boss")
        redisTemplate.delete("raid:boss")

        lifecycle.tick()

        val bosses = jdbcTemplate.queryForList("select * from raid_boss")
        assertThat(bosses).hasSize(1)
        assertThat(bosses[0]).containsEntry("status", "ALIVE").containsEntry("max_hp", 300).containsEntry("hp", 300)
        // 살아 있는 보스가 있으면 더 만들지 않는다
        lifecycle.tick()
        assertThat(aliveCount()).isEqualTo(1)
        assertThat(queryService.state(memberId = 1).boss!!.status).isEqualTo(RaidBossStatus.ALIVE)
    }

    @Test
    fun `US5-AC3 처치된 날에는 새 보스가 없고 다음 날 0시에 나타난다`() {
        val defeated = placeBoss("DEFEATED", participants = 7, endedAt = clock.instant())

        lifecycle.tick()

        assertThat(aliveCount()).isZero()
        val state = queryService.state(memberId = 1)
        assertThat(state.boss!!.bossId).isEqualTo(defeated)
        assertThat(state.nextBossAt).isEqualTo(nextMidnight())

        // 0시 1초 전에는 아직이다
        clock.advance(Duration.between(clock.instant(), nextMidnight()).minusSeconds(1))
        lifecycle.tick()
        assertThat(aliveCount()).isZero()

        clock.advance(Duration.ofSeconds(1))
        lifecycle.tick()

        // US5-AC4: 직전 참여자 7명 × 100
        val boss = aliveBoss()
        assertThat(boss).containsEntry("max_hp", 700).containsEntry("hp", 700)
        assertThat(boss["id"]).isNotEqualTo(defeated)
        // 화면이 읽는 값도 새 보스로 바뀐다
        val fresh = queryService.state(memberId = 1)
        assertThat(fresh.boss!!.bossId).isEqualTo(boss["id"])
        assertThat(fresh.boss!!.hp).isEqualTo(700)
        assertThat(fresh.nextBossAt).isNull()
    }

    @Test
    fun `US5-AC3 23시 59분에 끝나도 다음 날 0시에 나타난다`() {
        val almostMidnight = nextMidnight().minus(Duration.ofMinutes(1))
        clock.advance(Duration.between(clock.instant(), almostMidnight))
        placeBoss("DEFEATED", participants = 1, endedAt = clock.instant())

        lifecycle.tick()
        assertThat(aliveCount()).isZero()

        clock.advance(Duration.ofMinutes(1))
        lifecycle.tick()
        assertThat(aliveCount()).isEqualTo(1)
    }

    @Test
    fun `US5-AC5 7일 동안 처치되지 않으면 물러나고 다음 날 HP 300으로 나타난다`() {
        val bossId = placeBoss("ALIVE", participants = 0, maxHp = 900)
        val (member, late) = raid.memberIds(2)
        attackService.attack(member, bossId)

        clock.advance(Duration.ofDays(7).minusSeconds(1))
        lifecycle.tick()
        assertThat(raid.boss(bossId)).containsEntry("status", "ALIVE")

        clock.advance(Duration.ofSeconds(1))
        lifecycle.tick()

        assertThat(raid.boss(bossId))
            .containsEntry("status", "RETREATED")
            .containsEntry("hp", 899)
            .containsEntry("participant_count", 1)
        assertThat(raid.recordedDamage(bossId, member)).isEqualTo(1)
        // 물러난 보스는 공격을 받지 않는다
        assertThatThrownBy { attackService.attack(late, bossId) }
            .isInstanceOfSatisfying(BusinessException::class.java) {
                assertThat(it.errorCode).isEqualTo(ErrorCode.RAID_BOSS_ENDED)
            }
        assertThat(aliveCount()).isZero()

        clock.advance(Duration.between(clock.instant(), nextMidnight()))
        lifecycle.tick()
        // 참여자가 있었어도 물러난 보스 다음은 가장 작은 HP다
        assertThat(aliveBoss()).containsEntry("max_hp", 300)
    }

    @Test
    fun `US5-AC6 여러 인스턴스가 동시에 만들어도 살아 있는 보스는 하나다`() {
        placeBoss("DEFEATED", participants = 3, endedAt = clock.instant().minus(Duration.ofDays(2)))
        val pool = Executors.newFixedThreadPool(INSTANCES)
        val start = CountDownLatch(1)

        try {
            (1..INSTANCES)
                .map {
                    pool.submit(
                        Callable {
                            start.await()
                            lifecycle.tick()
                        },
                    )
                }.also { start.countDown() }
                .forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(aliveCount()).isEqualTo(1)
    }

    @Test
    fun `US5-AC8 보스의 감정을 셀 글에 숨긴 글, 지운 글, 7일보다 오래된 글은 들어가지 않는다`() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        val safety = SafetyFixture(mockMvc, jdbcTemplate)
        val author = MemberFixture(mockMvc).onboarded()
        val visible = safety.insertPost(author, "보이는 글")
        val hidden = safety.insertPost(author, "숨긴 글")
        val deleted = safety.insertPost(author, "지운 글")
        val old = safety.insertPost(author, "오래된 글")
        moderation.hide(ContentType.POST, hidden, "OPERATOR")
        jdbcTemplate.update("update posts set deleted_at = now() where id = ?", deleted)
        val real = Instant.now()
        jdbcTemplate.update("update posts set created_at = ? where id = ?", Timestamp.from(real.minus(EIGHT_DAYS)), old)

        val ids = postApi.visibleIdsSince(real.minus(Duration.ofDays(7)), limit = 10_000)

        assertThat(ids).contains(visible).doesNotContain(hidden, deleted, old)
        assertThat(ids).isSortedAccordingTo(reverseOrder())
        assertThat(postApi.visibleIdsSince(real.minus(Duration.ofDays(7)), limit = 1)).hasSize(1)
    }

    /** 지금 있는 보스를 오래전에 끝난 것으로 두고 새 보스를 놓는다. 끝난 보스면 [endedAt]에 끝난 것으로 한다. */
    private fun placeBoss(
        status: String,
        participants: Int,
        maxHp: Int = 300,
        endedAt: Instant? = null,
    ): Long {
        val now = clock.instant()
        jdbcTemplate.update(
            "update raid_boss set status = 'RETREATED', ended_at = ? where status = 'ALIVE'",
            Timestamp.from(now.minus(Duration.ofDays(30))),
        )
        val bossId =
            jdbcTemplate.queryForObject(
                """
                insert into raid_boss (emotion, max_hp, hp, participant_count, status, spawned_at, ended_at)
                values ('ANXIETY', ?, ?, ?, ?, ?, ?)
                returning id
                """.trimIndent(),
                Long::class.java,
                maxHp,
                if (status == "DEFEATED") 0 else maxHp,
                participants,
                status,
                Timestamp.from(now.truncatedTo(ChronoUnit.MICROS)),
                endedAt?.let { Timestamp.from(it.truncatedTo(ChronoUnit.MICROS)) },
            )!!
        redisTemplate.delete("raid:boss")
        return bossId
    }

    private fun aliveCount(): Int {
        val sql = "select count(*) from raid_boss where $ALIVE"
        return jdbcTemplate.queryForObject(sql, Int::class.java)!!
    }

    private fun aliveBoss(): Map<String, Any?> = jdbcTemplate.queryForMap("select * from raid_boss where $ALIVE")

    private fun nextMidnight(): Instant =
        clock
            .instant()
            .atZone(SEOUL)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(SEOUL)
            .toInstant()

    private fun nextNoon(): Instant {
        val today = clock.instant().atZone(SEOUL)
        val noon = today.toLocalDate().atTime(LocalTime.NOON).atZone(SEOUL)
        return (if (noon.isAfter(today)) noon else noon.plusDays(1)).toInstant()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ClockOverride {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS))
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val EIGHT_DAYS: Duration = Duration.ofDays(8)
        const val INSTANCES = 8
        const val ALIVE = "status = 'ALIVE'"
    }
}
