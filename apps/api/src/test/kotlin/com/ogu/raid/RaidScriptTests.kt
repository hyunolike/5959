package com.ogu.raid

import com.ogu.TestcontainersConfiguration
import com.ogu.raid.application.RaidLoader
import com.ogu.raid.domain.AttackOutcome
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidRedis
import com.ogu.support.RaidFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant

/** T006: 레이드의 Redis 스크립트 규칙(006 research R2~R5). 스크립트를 실제 Redis에 돌린다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RaidScriptTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var redis: RaidRedis

    @Autowired
    lateinit var loader: RaidLoader

    private lateinit var raid: RaidFixture

    @BeforeEach
    fun setUp() {
        raid = RaidFixture(jdbcTemplate, redisTemplate)
    }

    @Test
    fun `US1-AC3 쿨다운 안의 공격은 HP와 기여를 바꾸지 않고 만료를 늘리지 않는다`() {
        val bossId = loadedBoss(maxHp = 10)
        val (member) = raid.memberIds(1)

        val first = attack(bossId, member, cooldown = Duration.ofSeconds(5))
        val second = attack(bossId, member, cooldown = Duration.ofSeconds(5))
        val third = attack(bossId, member, cooldown = Duration.ofSeconds(5))

        assertThat(first).isInstanceOf(AttackOutcome.Accepted::class.java)
        assertThat(second).isInstanceOf(AttackOutcome.Cooldown::class.java)
        // 거절된 요청이 만료를 다시 걸지 않는다. 남은 시간은 줄어들기만 한다
        val remaining = (third as AttackOutcome.Cooldown).remaining
        assertThat(remaining).isLessThanOrEqualTo((second as AttackOutcome.Cooldown).remaining)
        assertThat(remaining).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(5))
        assertThat(raid.redisHp()).isEqualTo(9)
        assertThat(redis.damageOf(bossId, member)).isEqualTo(1)
    }

    @Test
    fun `US1-AC5 참여자 수는 회원마다 한 번만 는다`() {
        val bossId = loadedBoss(maxHp = 10)
        val (first, second) = raid.memberIds(2)

        val one = attack(bossId, first) as AttackOutcome.Accepted
        raid.clearCooldown(first)
        val again = attack(bossId, first) as AttackOutcome.Accepted
        val other = attack(bossId, second) as AttackOutcome.Accepted

        val counts = listOf(one, again, other).map { it.participantCount }
        assertThat(counts).containsExactly(1, 1, 2)
        assertThat(listOf(one.myDamage, again.myDamage, other.myDamage)).containsExactly(1, 2, 1)
        assertThat(listOf(one.hp, again.hp, other.hp)).containsExactly(9, 8, 7)
        assertThat(other.maxHp).isEqualTo(10)
    }

    @Test
    fun `US3-AC2 HP는 0에서 멈추고 처치로 바꾸는 공격은 하나뿐이며 그 뒤는 끝났다고 답한다`() {
        val bossId = loadedBoss(maxHp = 3)
        val members = raid.memberIds(5)

        val outcomes = members.map { attack(bossId, it) }

        val accepted = outcomes.filterIsInstance<AttackOutcome.Accepted>()
        assertThat(accepted.map { it.hp }).containsExactly(2, 1, 0)
        assertThat(accepted.map { it.defeated }).containsExactly(false, false, true)
        assertThat(outcomes.drop(3)).containsOnly(AttackOutcome.Ended)
        assertThat(raid.redisHp()).isZero()
        val boss = redis.read()!!
        assertThat(boss.status).isEqualTo(RaidBossStatus.DEFEATED)
        assertThat(boss.endedAt).isNotNull()
        assertThat(boss.participantCount).isEqualTo(3)
        // 끝난 보스는 쿨다운을 걸지 않는다. 거절된 회원이 다음 보스에서 기다리지 않는다
        assertThat(redisTemplate.hasKey("raid:cd:${members[4]}")).isFalse()
    }

    @Test
    fun `US3-AC4 기여의 합은 줄어든 HP와 같다`() {
        val bossId = loadedBoss(maxHp = 50)
        val members = raid.memberIds(7)

        repeat(3) {
            members.forEach { member ->
                raid.clearCooldown(member)
                attack(bossId, member)
            }
        }

        assertThat(raid.redisDamageSum(bossId)).isEqualTo(21)
        assertThat(50 - raid.redisHp()).isEqualTo(21)
    }

    @Test
    fun `요청의 보스가 지금 보스가 아니면 끝났다고 답하고, 더 새 보스면 다시 채우라고 답한다`() {
        val old = loadedBoss(maxHp = 5)
        val (member) = raid.memberIds(1)

        assertThat(attack(old - 1, member)).isEqualTo(AttackOutcome.Ended)
        assertThat(attack(old + 1_000_000, member)).isEqualTo(AttackOutcome.Reload)
        assertThat(raid.redisHp()).isEqualTo(5)

        // Redis가 비어 있으면 다시 채우라고 답한다
        redisTemplate.delete(RaidRedis.BOSS_KEY)
        assertThat(attack(old, member)).isEqualTo(AttackOutcome.Reload)
    }

    @Test
    fun `옮기기는 아직 적지 않은 회원만 꺼내고 되돌린 회원은 다시 꺼낸다`() {
        val bossId = loadedBoss(maxHp = 20)
        val members = raid.memberIds(3)
        members.forEach { attack(bossId, it) }
        // 다른 컨텍스트의 주기 작업이 먼저 꺼냈을 수 있다. 꺼낼 것을 다시 표시해 이 테스트가 꺼내게 한다
        redis.markDirty(bossId, members)

        val batch = redis.flush(limit = 500)!!

        assertThat(batch.bossId).isEqualTo(bossId)
        assertThat(batch.hp).isEqualTo(17)
        assertThat(batch.status).isEqualTo(RaidBossStatus.ALIVE)
        assertThat(batch.participantCount).isEqualTo(3)
        assertThat(batch.damages).containsOnlyKeys(members).containsValues(1)
        assertThat(redis.flush(limit = 500)!!.damages.keys).doesNotContainAnyElementsOf(members)

        redis.markDirty(bossId, members.take(1))
        assertThat(redis.flush(limit = 500)!!.damages).containsKey(members[0])
    }

    @Test
    fun `물러나게 하면 공격을 받지 않고, 이미 끝난 보스는 다시 끝내지 않는다`() {
        val bossId = loadedBoss(maxHp = 5)
        val (member) = raid.memberIds(1)

        assertThat(redis.end(bossId, RaidBossStatus.RETREATED, Instant.now())).isTrue()
        assertThat(redis.end(bossId, RaidBossStatus.RETREATED, Instant.now())).isFalse()

        assertThat(attack(bossId, member)).isEqualTo(AttackOutcome.Ended)
        assertThat(redis.read()!!.status).isEqualTo(RaidBossStatus.RETREATED)
    }

    @Test
    fun `같은 보스는 다시 올리지 않고, 새 보스를 올리면 바뀌며 epoch가 실린다`() {
        val first = loadedBoss(maxHp = 5)
        val (member) = raid.memberIds(1)
        attack(first, member)
        val loaded = redis.read()!!

        // 같은 보스를 다시 올려도 Redis의 지금 값이 남는다
        assertThat(redis.load(loaded.copy(hp = 5), emptyMap(), epoch = 1)).isFalse()
        assertThat(raid.redisHp()).isEqualTo(4)

        val second = raid.freshBoss(maxHp = 7)
        loader.ensureLoaded()
        val replaced = redis.read()!!
        assertThat(replaced.id).isEqualTo(second)
        assertThat(replaced.hp).isEqualTo(7)
        assertThat(replaced.epoch).isGreaterThan(0)
    }

    private fun loadedBoss(maxHp: Int): Long {
        val bossId = raid.freshBoss(maxHp)
        loader.ensureLoaded()
        return bossId
    }

    private fun attack(
        bossId: Long,
        memberId: Long,
        cooldown: Duration = Duration.ofSeconds(1),
    ): AttackOutcome = redis.attack(bossId, memberId, cooldown, Instant.now())
}
