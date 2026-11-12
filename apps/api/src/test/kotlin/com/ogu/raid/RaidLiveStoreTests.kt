package com.ogu.raid

import com.ogu.emotion.EmotionType
import com.ogu.raid.domain.AttackOutcome
import com.ogu.raid.domain.RaidBoss
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidLiveStore
import com.ogu.raid.domain.RaidUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** 018 T027: 레이드의 메모리 저장소 규칙(research R5). 옛 Redis 스크립트의 규칙을 그대로 옮겼다. */
class RaidLiveStoreTests {
    private val start = Instant.parse("2026-11-11T03:00:00Z")
    private val store = RaidLiveStore(Clock.fixed(start, ZoneOffset.UTC))

    @Test
    fun `보스가 없거나 요청의 보스보다 옛 보스면 다시 올리라고 답한다`() {
        assertThat(attack(BOSS, MEMBER)).isEqualTo(AttackOutcome.Reload)

        load(maxHp = 5)

        assertThat(attack(BOSS + 1, MEMBER)).isEqualTo(AttackOutcome.Reload)
        assertThat(store.read()!!.hp).isEqualTo(5)
    }

    @Test
    fun `요청의 보스가 지금 보스보다 옛 것이거나 보스가 끝났으면 끝났다고 답한다`() {
        load(maxHp = 5)

        assertThat(attack(BOSS - 1, MEMBER)).isEqualTo(AttackOutcome.Ended)
        assertThat(store.end(BOSS, RaidBossStatus.RETREATED, start)).isTrue()
        assertThat(attack(BOSS, MEMBER)).isEqualTo(AttackOutcome.Ended)
        assertThat(store.read()!!.hp).isEqualTo(5)
    }

    @Test
    fun `쿨다운 안의 공격은 남은 시간을 주고 HP와 기여를 바꾸지 않으며 만료를 늘리지 않는다`() {
        load(maxHp = 10)
        val cooldown = Duration.ofSeconds(5)

        val first = attack(BOSS, MEMBER, cooldown, start)
        val second = attack(BOSS, MEMBER, cooldown, start.plusSeconds(1))
        val third = attack(BOSS, MEMBER, cooldown, start.plusSeconds(3))
        val after = attack(BOSS, MEMBER, cooldown, start.plusSeconds(5))

        assertThat(first).isInstanceOf(AttackOutcome.Accepted::class.java)
        assertThat(second).isEqualTo(AttackOutcome.Cooldown(Duration.ofSeconds(4)))
        // 거절된 요청이 만료를 다시 걸지 않는다
        assertThat(third).isEqualTo(AttackOutcome.Cooldown(Duration.ofSeconds(2)))
        assertThat(after).isInstanceOf(AttackOutcome.Accepted::class.java)
        assertThat(store.read()!!.hp).isEqualTo(8)
        assertThat(store.damageOf(BOSS, MEMBER)).isEqualTo(2)
    }

    @Test
    fun `공격은 HP를 하나 줄이고 기여를 하나 늘리며 바뀐 회원에 넣는다`() {
        load(maxHp = 10)

        val one = attack(BOSS, MEMBER) as AttackOutcome.Accepted
        store.clearCooldown(MEMBER)
        val again = attack(BOSS, MEMBER) as AttackOutcome.Accepted
        val other = attack(BOSS, OTHER) as AttackOutcome.Accepted

        assertThat(listOf(one.hp, again.hp, other.hp)).containsExactly(9, 8, 7)
        assertThat(listOf(one.myDamage, again.myDamage, other.myDamage)).containsExactly(1, 2, 1)
        // 참여자 수는 회원마다 한 번만 는다
        assertThat(listOf(one.participantCount, again.participantCount, other.participantCount))
            .containsExactly(1, 1, 2)
        assertThat(other.maxHp).isEqualTo(10)
        assertThat(store.flush(limit = 500)!!.damages).isEqualTo(mapOf(MEMBER to 2, OTHER to 1))
    }

    @Test
    fun `HP는 0에서 멈추고 처치로 바꾸는 공격은 하나뿐이며 끝난 보스는 쿨다운을 걸지 않는다`() {
        load(maxHp = 3)
        val members = (1L..5L).toList()

        val outcomes = members.map { attack(BOSS, it) }

        val accepted = outcomes.filterIsInstance<AttackOutcome.Accepted>()
        assertThat(accepted.map { it.hp }).containsExactly(2, 1, 0)
        assertThat(accepted.map { it.defeated }).containsExactly(false, false, true)
        assertThat(outcomes.drop(3)).containsOnly(AttackOutcome.Ended)
        val boss = store.read()!!
        assertThat(boss.hp).isZero()
        assertThat(boss.status).isEqualTo(RaidBossStatus.DEFEATED)
        assertThat(boss.endedAt).isEqualTo(start)
        assertThat(boss.participantCount).isEqualTo(3)
        // 거절된 회원은 다음 보스에서 기다리지 않는다
        loadNext(maxHp = 3)
        assertThat(attack(BOSS + 1, members[4])).isInstanceOf(AttackOutcome.Accepted::class.java)
    }

    @Test
    fun `끝내기는 살아 있는 지금 보스만 끝낸다`() {
        load(maxHp = 5)

        assertThat(store.end(BOSS + 1, RaidBossStatus.RETREATED, start)).isFalse()
        assertThat(store.end(BOSS, RaidBossStatus.RETREATED, start)).isTrue()
        assertThat(store.end(BOSS, RaidBossStatus.RETREATED, start)).isFalse()

        val boss = store.read()!!
        assertThat(boss.status).isEqualTo(RaidBossStatus.RETREATED)
        assertThat(boss.endedAt).isEqualTo(start)
    }

    @Test
    fun `올리기는 더 새 보스만 받고 그때 지난 보스의 기여와 바뀐 회원을 비운다`() {
        load(maxHp = 5, damages = mapOf(OTHER to 2))
        attack(BOSS, MEMBER)

        // 같은 보스를 다시 올려도 지금 값이 남는다
        assertThat(store.load(boss(BOSS, maxHp = 5), emptyMap(), epoch = 2)).isFalse()
        assertThat(store.read()!!.hp).isEqualTo(2)
        assertThat(store.read()!!.epoch).isEqualTo(1)

        assertThat(loadNext(maxHp = 7)).isTrue()

        val next = store.read()!!
        assertThat(next.id).isEqualTo(BOSS + 1)
        assertThat(next.hp).isEqualTo(7)
        assertThat(next.participantCount).isZero()
        assertThat(next.epoch).isEqualTo(2)
        assertThat(store.contributions(BOSS)).isEmpty()
        assertThat(store.flush(limit = 500)!!.damages).isEmpty()
    }

    @Test
    fun `꺼내기는 바뀐 회원만 꺼내고 되돌린 회원은 다시 꺼낸다`() {
        load(maxHp = 20, damages = mapOf(9L to 4))
        val members = listOf(1L, 2L, 3L)
        members.forEach { attack(BOSS, it) }

        val batch = store.flush(limit = 2)!!
        val rest = store.flush(limit = 2)!!

        assertThat(batch.bossId).isEqualTo(BOSS)
        assertThat(batch.hp).isEqualTo(20 - 4 - 3)
        assertThat(batch.status).isEqualTo(RaidBossStatus.ALIVE)
        assertThat(batch.participantCount).isEqualTo(4)
        assertThat(batch.damages.keys + rest.damages.keys).containsExactlyInAnyOrderElementsOf(members)
        assertThat(store.flush(limit = 2)!!.damages).isEmpty()

        // 적다 실패하면 되돌린다. 다른 보스의 회원은 받지 않는다
        store.markDirty(BOSS, batch.damages.keys)
        store.markDirty(BOSS + 1, listOf(3L))
        assertThat(store.flush(limit = 500)!!.damages).isEqualTo(batch.damages)
    }

    @Test
    fun `보스가 없으면 꺼낼 것이 없다`() {
        assertThat(store.flush(limit = 500)).isNull()
        assertThat(store.read()).isNull()
    }

    @Test
    fun `맞추기는 회원마다 큰 값을 남기고 HP를 기여의 합으로 다시 센다`() {
        load(maxHp = 30, damages = mapOf(MEMBER to 2))
        attack(BOSS, MEMBER)
        attack(BOSS, OTHER)

        // 옛 인스턴스가 끝나며 적은 값. MEMBER는 이쪽이 더 크고, OTHER는 기록이 더 크며, 처음 보는 회원도 있다
        store.reconcile(boss(BOSS, maxHp = 30), mapOf(MEMBER to 1, OTHER to 4, NEWCOMER to 2), start)

        assertThat(store.contributions(BOSS)).isEqualTo(mapOf(MEMBER to 3, OTHER to 4, NEWCOMER to 2))
        val boss = store.read()!!
        assertThat(boss.hp).isEqualTo(30 - 9)
        assertThat(boss.participantCount).isEqualTo(3)
        assertThat(boss.status).isEqualTo(RaidBossStatus.ALIVE)
    }

    @Test
    fun `맞춘 기여가 HP를 넘으면 0에서 멈추고 처치로 바꾼다`() {
        load(maxHp = 5, damages = mapOf(MEMBER to 2))

        store.reconcile(boss(BOSS, maxHp = 5), mapOf(OTHER to 4), start)

        val boss = store.read()!!
        assertThat(boss.hp).isZero()
        assertThat(boss.status).isEqualTo(RaidBossStatus.DEFEATED)
        assertThat(boss.endedAt).isEqualTo(start)
    }

    @Test
    fun `기록에서 끝난 보스는 맞추면 끝난 채가 되고, 다른 보스의 기록은 받지 않는다`() {
        load(maxHp = 5)
        val endedAt = start.minusSeconds(10)
        val retreated = boss(BOSS, maxHp = 5).copy(status = RaidBossStatus.RETREATED, endedAt = endedAt)

        store.reconcile(boss(BOSS + 1, maxHp = 5), mapOf(OTHER to 4), start)
        assertThat(store.contributions(BOSS)).isEmpty()

        store.reconcile(retreated, mapOf(OTHER to 1), start)
        val boss = store.read()!!
        assertThat(boss.status).isEqualTo(RaidBossStatus.RETREATED)
        assertThat(boss.endedAt).isEqualTo(endedAt)
        assertThat(boss.hp).isEqualTo(4)
        assertThat(attack(BOSS, MEMBER)).isEqualTo(AttackOutcome.Ended)
    }

    @Test
    fun `받지 않는 동안 공격과 조회는 RaidUnavailableException이고 꺼내기는 된다`() {
        load(maxHp = 5)
        attack(BOSS, MEMBER)

        store.stopAccepting()

        assertThatThrownBy { attack(BOSS, OTHER) }.isInstanceOf(RaidUnavailableException::class.java)
        assertThatThrownBy { store.read() }.isInstanceOf(RaidUnavailableException::class.java)
        assertThat(store.flush(limit = 500)!!.damages).isEqualTo(mapOf(MEMBER to 1))
        store.resumeAccepting()
        assertThat(attack(BOSS, OTHER)).isInstanceOf(AttackOutcome.Accepted::class.java)
    }

    @Test
    fun `비우면 보스와 기여와 쿨다운이 모두 사라진다`() {
        load(maxHp = 5)
        attack(BOSS, MEMBER, Duration.ofHours(1))

        store.clear()

        assertThat(store.read()).isNull()
        load(maxHp = 5)
        assertThat(attack(BOSS, MEMBER)).isInstanceOf(AttackOutcome.Accepted::class.java)
    }

    private fun load(
        maxHp: Int,
        damages: Map<Long, Int> = emptyMap(),
    ) = store.load(boss(BOSS, maxHp).copy(hp = maxHp - damages.values.sum()), damages, epoch = 1)

    private fun loadNext(maxHp: Int) = store.load(boss(BOSS + 1, maxHp), emptyMap(), epoch = 2)

    private fun boss(
        id: Long,
        maxHp: Int,
    ) = RaidBoss(
        id = id,
        emotion = EmotionType.LETHARGY,
        maxHp = maxHp,
        hp = maxHp,
        status = RaidBossStatus.ALIVE,
        participantCount = 0,
        spawnedAt = start.minusSeconds(60),
        endedAt = null,
    )

    private fun attack(
        bossId: Long,
        memberId: Long,
        cooldown: Duration = Duration.ofSeconds(1),
        now: Instant = start,
    ): AttackOutcome = store.attack(bossId, memberId, cooldown, now)

    private companion object {
        const val BOSS = 100L
        const val MEMBER = 1L
        const val OTHER = 2L
        const val NEWCOMER = 3L
    }
}
