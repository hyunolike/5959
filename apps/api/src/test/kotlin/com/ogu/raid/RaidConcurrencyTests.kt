package com.ogu.raid

import com.ogu.TestcontainersConfiguration
import com.ogu.raid.application.RaidAttackService
import com.ogu.raid.application.RaidFlusher
import com.ogu.raid.application.RaidQueryService
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.support.RaidFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T009: 한꺼번에 몰려도 HP와 처치가 정확하다(006 US3, SC-001, SC-002). 가상 사용자 500명의 측정은 k6로 따로 하고
 * (research R15), 같은 불변식을 여기서 PR마다 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RaidConcurrencyTests {
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

    private lateinit var raid: RaidFixture

    @BeforeEach
    fun setUp() {
        raid = RaidFixture(jdbcTemplate, redisTemplate)
    }

    @Test
    fun `US3-AC1 회원 64명이 동시에 여러 번 공격해도 줄어든 HP가 받아들여진 수와 같다`() {
        val bossId = loadedBoss(maxHp = 5_000)
        val members = raid.memberIds(ATTACKERS)

        // 회원마다 다섯 번씩, 쿨다운을 지우며 공격한다. 쿨다운에 걸린 것은 받아들여지지 않은 것으로 센다
        val accepted =
            together(members) { member ->
                (1..ROUNDS).count {
                    raid.clearCooldown(member)
                    attackOrNull(member, bossId) != null
                }
            }.sum()

        assertThat(accepted).isGreaterThan(0)
        assertThat(5_000 - raid.redisHp()).isEqualTo(accepted)
        // US3-AC4: 기여의 합도 같다. Redis와, 옮긴 뒤의 기록 모두
        assertThat(raid.redisDamageSum(bossId)).isEqualTo(accepted)
        flusher.flush()
        raid.awaitRecorded(bossId, accepted)
        assertThat(raid.boss(bossId))
            .containsEntry("hp", 5_000 - accepted)
            .containsEntry("participant_count", ATTACKERS)
    }

    @Test
    fun `US3-AC2 남은 HP보다 많은 공격이 동시에 와도 0에서 멈추고 남은 만큼만 받아들인다`() {
        val bossId = loadedBoss(maxHp = 20)
        val members = raid.memberIds(ATTACKERS)

        val results = together(members) { member -> attackOrNull(member, bossId) }

        val accepted = results.filterNotNull()
        assertThat(accepted).hasSize(20)
        assertThat(accepted.map { it.hp }).containsExactlyInAnyOrderElementsOf((0..19).toList())
        assertThat(raid.redisHp()).isZero()
        // US3-AC3: 처치는 한 번이다
        assertThat(accepted.count { it.defeated }).isEqualTo(1)
        raid.awaitStatus(bossId, "DEFEATED")
        assertThat(raid.boss(bossId)).containsEntry("hp", 0).containsEntry("participant_count", 20)
        // US3-AC4
        assertThat(raid.recordedDamageSum(bossId)).isEqualTo(20)
    }

    @Test
    fun `US3-AC3 마지막 HP를 여러 회원이 다퉈도 처치는 한 번만 기록된다`() {
        repeat(REPEATS) {
            val bossId = loadedBoss(maxHp = 1)
            val members = raid.memberIds(ATTACKERS)

            val results = together(members) { member -> attackOrNull(member, bossId) }

            assertThat(results.filterNotNull()).hasSize(1)
            assertThat(results.filterNotNull().single().defeated).isTrue()
            raid.awaitStatus(bossId, "DEFEATED")
            assertThat(raid.recordedDamageSum(bossId)).isEqualTo(1)
            assertThat(raid.boss(bossId)["ended_at"]).isNotNull()
        }
    }

    private fun loadedBoss(maxHp: Int): Long {
        val bossId = raid.freshBoss(maxHp)
        queryService.current()
        return bossId
    }

    /** 받아들여졌으면 결과, 쿨다운이거나 보스가 끝났으면 null. 그 밖의 실패는 테스트를 깨뜨린다. */
    private fun attackOrNull(
        memberId: Long,
        bossId: Long,
    ) = try {
        attackService.attack(memberId, bossId)
    } catch (e: BusinessException) {
        check(e.errorCode == ErrorCode.RAID_COOLDOWN || e.errorCode == ErrorCode.RAID_BOSS_ENDED) { "예상하지 못한 거절: $e" }
        null
    }

    private fun <T> together(
        members: List<Long>,
        action: (Long) -> T,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(members.size)
        val start = CountDownLatch(1)
        return try {
            members
                .map { member ->
                    pool.submit(
                        Callable {
                            start.await()
                            action(member)
                        },
                    )
                }.also { start.countDown() }
                .map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val ATTACKERS = 64
        const val ROUNDS = 5
        const val REPEATS = 5
    }
}
