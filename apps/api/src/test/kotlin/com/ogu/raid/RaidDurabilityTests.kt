package com.ogu.raid

import com.ogu.TestcontainersConfiguration
import com.ogu.raid.application.RaidAttackService
import com.ogu.raid.application.RaidFlusher
import com.ogu.raid.application.RaidQueryService
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidContributionRepository
import com.ogu.raid.domain.RaidRedis
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.support.RaidFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/** T012: 옮기기와 다시 채우기(006 US3-AC5, AC8, research R3~R5). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RaidDurabilityTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var attackService: RaidAttackService

    @Autowired
    lateinit var queryService: RaidQueryService

    @Autowired
    lateinit var flusher: RaidFlusher

    @Autowired
    lateinit var redis: RaidRedis

    @Autowired
    lateinit var contributions: RaidContributionRepository

    private lateinit var raid: RaidFixture

    @BeforeEach
    fun setUp() {
        raid = RaidFixture(jdbcTemplate, redisTemplate)
    }

    @Test
    fun `US3-AC5 받아들여진 공격은 API가 다시 떠도 남는다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val (first, second) = raid.memberIds(2)
        attackService.attack(first, bossId)
        attackService.attack(second, bossId)

        // API가 다시 뜬 것은 메모리의 상태가 사라진 것이다. 레이드의 상태는 Redis에 있어 새 조회가 그대로 읽는다
        val state = queryService.state(first)

        assertThat(state.boss!!.hp).isEqualTo(28)
        assertThat(state.boss!!.participantCount).isEqualTo(2)
        assertThat(state.myDamage).isEqualTo(1)
        assertThat(state.available).isTrue()
    }

    @Test
    fun `US3-AC8 Redis가 비면 마지막으로 기록된 값에서 이어지고 epoch가 커진다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val (first, second, third) = raid.memberIds(3)
        attackService.attack(first, bossId)
        attackService.attack(second, bossId)
        val before = queryService.state(first).epoch
        flusher.flush()
        raid.awaitRecorded(bossId, 2)
        Thread.sleep(2)

        // Redis가 다시 떠서 레이드의 키가 모두 사라졌다
        wipeRedis(bossId)
        val result = attackService.attack(third, bossId)

        assertThat(result.hp).isEqualTo(27)
        assertThat(result.participantCount).isEqualTo(3)
        val state = queryService.state(first)
        assertThat(state.myDamage).isEqualTo(1)
        assertThat(state.epoch).isGreaterThan(before)
        // 다시 채운 뒤에도 기여의 합이 줄어든 HP와 같다
        assertThat(raid.redisDamageSum(bossId)).isEqualTo(30 - raid.redisHp())
    }

    @Test
    fun `US3-AC8 옮기기 전에 Redis가 비면 그 공격만 사라지고 기여와 HP는 서로 맞는다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val (first, second) = raid.memberIds(2)
        attackService.attack(first, bossId)
        flusher.flush()
        raid.awaitRecorded(bossId, 1)
        // 둘째 공격은 아직 옮기지 못했다고 본다. 기록에서 지워 그 상태를 만든다
        attackService.attack(second, bossId)
        wipeRedis(bossId)
        jdbcTemplate.update("delete from raid_contribution where boss_id = ? and member_id = ?", bossId, second)
        jdbcTemplate.update("update raid_boss set hp = 29, participant_count = 1 where id = ?", bossId)

        val state = queryService.state(second)

        assertThat(state.boss!!.hp).isEqualTo(29)
        assertThat(state.boss!!.participantCount).isEqualTo(1)
        assertThat(state.myDamage).isZero()
    }

    @Test
    fun `US3-AC8 처치된 보스는 Redis가 비어도 처치된 채이고 공격을 받지 않는다`() {
        val bossId = raid.freshBoss(maxHp = 1)
        val (first, second) = raid.memberIds(2)
        assertThat(attackService.attack(first, bossId).defeated).isTrue()

        wipeRedis(bossId)

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
        attackService.attack(first, bossId)
        attackService.attack(second, bossId)
        raid.awaitStatus(bossId, "DEFEATED")
        // 처치는 Redis에만 남고 기록은 살아 있는 상태로 되돌린다
        jdbcTemplate.update("update raid_boss set status = 'ALIVE', ended_at = null, hp = 1 where id = ?", bossId)
        redis.markDirty(bossId, listOf(first))

        flusher.flush()

        raid.awaitStatus(bossId, "DEFEATED")
        assertThat(raid.boss(bossId)).containsEntry("hp", 0).containsEntry("participant_count", 2)
        assertThat(raid.recordedDamageSum(bossId)).isEqualTo(2)
    }

    private fun wipeRedis(bossId: Long) {
        redisTemplate.delete(listOf(RaidRedis.BOSS_KEY, RaidRedis.contributionsKey(bossId), RaidRedis.dirtyKey(bossId)))
    }
}
