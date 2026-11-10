package com.ogu.raid.domain

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 레이드가 지금 공격을 받지 않는다(종료 중, 018 research R5). 공격은 503, 조회는 마지막 기록으로 답한다. */
class RaidUnavailableException : RuntimeException("레이드가 지금 공격을 받지 않습니다")

/** 공격 한 번의 결과(006 data-model.md "공격 스크립트의 결과"). */
sealed interface AttackOutcome {
    /** 보스가 없거나 지난 보스가 남아 있다. 기록에서 다시 올리고 한 번 더 시도한다. */
    data object Reload : AttackOutcome

    data object Ended : AttackOutcome

    data class Cooldown(
        val remaining: Duration,
    ) : AttackOutcome

    data class Accepted(
        val hp: Int,
        val maxHp: Int,
        val myDamage: Int,
        val participantCount: Int,
        val defeated: Boolean,
    ) : AttackOutcome
}

/** 옮길 것 한 묶음. [damages]는 아직 Postgres에 적지 않은 회원의 지금 기여다. */
data class RaidFlush(
    val bossId: Long,
    val hp: Int,
    val status: RaidBossStatus,
    val participantCount: Int,
    val endedAt: Instant?,
    val damages: Map<Long, Int>,
)

/**
 * 살아 있는 보스의 지금 값(018 data-model.md 4, research R5). 서버 한 대의 메모리에 두고 모든 읽기와 쓰기를 잠금 하나
 * 안에서 한다. 공격 한 번의 단계(쿨다운, HP, 기여, 바뀐 회원, 처치)가 다른 요청과 섞이지 않는다. Postgres에는
 * [com.ogu.raid.application.RaidFlusher]가 뒤따라 적는다.
 *
 * 종료 중([stopAccepting])에는 공격과 조회가 [RaidUnavailableException]이다. 꺼내기와 되돌리기는 그대로 되어 남은 것을
 * 모두 적을 수 있다.
 */
@Component
@Suppress("TooManyFunctions") // 옛 RaidRedis의 연산을 같은 이름으로 옮기고, 맞추기와 종료 단계가 더해졌다
class RaidLiveStore(
    private val clock: Clock,
) {
    private val lock = ReentrantLock()
    private val loadLock = ReentrantLock()
    private var boss: RaidBoss? = null
    private val contributions = HashMap<Long, Int>()
    private val dirty = LinkedHashSet<Long>()
    private val cooldownUntil = HashMap<Long, Instant>()
    private var accepting = true

    /**
     * 공격 한 번. 순서는 옛 공격 스크립트와 같다. 보스가 없거나 옛 보스면 다시 올리라고, 다른 보스거나 끝났으면 끝났다고,
     * 쿨다운 안이면 남은 시간을 답한다. 쿨다운은 걸려 있으면 거절만 하고 늘리지 않는다.
     */
    fun attack(
        bossId: Long,
        memberId: Long,
        cooldown: Duration,
        now: Instant,
    ): AttackOutcome =
        lock.withLock {
            checkAccepting()
            val current = boss
            if (current == null || current.id < bossId) return AttackOutcome.Reload
            if (current.id != bossId || current.status != RaidBossStatus.ALIVE) return AttackOutcome.Ended
            val until = cooldownUntil[memberId]
            if (until != null && until > now) return AttackOutcome.Cooldown(Duration.between(now, until))
            cooldownUntil[memberId] = now.plus(cooldown)

            val hp = current.hp - 1
            val mine = contributions.merge(memberId, 1, Int::plus)!!
            dirty += memberId
            val defeated = hp <= 0
            boss =
                if (defeated) {
                    current.copy(hp = 0, status = RaidBossStatus.DEFEATED, endedAt = now)
                } else {
                    current.copy(hp = hp)
                }
            AttackOutcome.Accepted(hp.coerceAtLeast(0), current.maxHp, mine, contributions.size, defeated)
        }

    /** 지금의 보스. 올린 적이 없으면 null이다. */
    fun read(): RaidBoss? =
        lock.withLock {
            checkAccepting()
            boss?.copy(participantCount = contributions.size)
        }

    fun damageOf(
        bossId: Long,
        memberId: Long,
    ): Int =
        lock.withLock {
            checkAccepting()
            if (boss?.id == bossId) contributions[memberId] ?: 0 else 0
        }

    /** 보스의 모든 기여. 보스를 끝낼 때 통째로 적는 데 쓴다. 지금 보스가 아니면 비어 있다. */
    fun contributions(bossId: Long): Map<Long, Int> {
        lock.withLock { return if (boss?.id == bossId) contributions.toMap() else emptyMap() }
    }

    /**
     * 아직 적지 않은 회원을 [limit]명까지 꺼내며 그 기여와 지금 HP를 함께 읽는다. 보스가 없으면 null이다. 만료된 쿨다운도
     * 이때 지운다.
     */
    fun flush(limit: Int): RaidFlush? =
        lock.withLock {
            val current = boss ?: return null
            val now = clock.instant()
            cooldownUntil.values.removeIf { it <= now }
            val members = dirty.take(limit)
            dirty.removeAll(members.toSet())
            RaidFlush(
                bossId = current.id,
                hp = current.hp.coerceAtLeast(0),
                status = current.status,
                participantCount = contributions.size,
                endedAt = current.endedAt,
                damages = members.associateWith { contributions.getValue(it) },
            )
        }

    /** 적지 못한 회원을 다음 꺼내기가 다시 꺼내도록 되돌린다. 그 사이 보스가 바뀌었으면 버린다. */
    fun markDirty(
        bossId: Long,
        memberIds: Collection<Long>,
    ) {
        if (memberIds.isEmpty()) return
        lock.withLock { if (boss?.id == bossId) dirty += memberIds }
    }

    /** 살아 있는 보스를 [status]로 끝낸다. 이미 끝났거나 다른 보스면 false다. */
    fun end(
        bossId: Long,
        status: RaidBossStatus,
        now: Instant,
    ): Boolean =
        lock.withLock {
            val current = boss
            if (current == null || current.id != bossId || current.status != RaidBossStatus.ALIVE) return false
            boss = current.copy(status = status, endedAt = now)
            true
        }

    /**
     * 기록에서 읽은 보스와 기여를 올린다. 같은 보스나 더 새 보스가 이미 있으면 아무것도 바꾸지 않는다. 지난 보스의 기여와
     * 바뀐 회원은 이때 비운다. 쿨다운은 회원의 것이라 남긴다.
     */
    fun load(
        boss: RaidBoss,
        damages: Map<Long, Int>,
        epoch: Long,
    ): Boolean =
        lock.withLock {
            val current = this.boss
            if (current != null && current.id >= boss.id) return false
            contributions.clear()
            contributions.putAll(damages)
            dirty.clear()
            this.boss = boss.copy(participantCount = damages.size, epoch = epoch)
            true
        }

    /**
     * 기록과 맞춘다(018 research R6). 배포가 겹칠 때 옛 인스턴스가 끝나며 적은 공격을 받아들인다. 회원마다 큰 값을 남기고
     * HP를 `maxHp − 기여의 합`(0에서 멈춤)으로 다시 센다. 기록에서 끝난 보스면 끝난 채로 두고, 살아 있는데 HP가 0이
     * 되면 처치로 바꾼다(기록은 다음 옮기기가 마무리한다). 지금 보스가 아니면 아무것도 하지 않는다.
     */
    fun reconcile(
        record: RaidBoss,
        damages: Map<Long, Int>,
        now: Instant,
    ) {
        lock.withLock {
            val current = boss?.takeIf { it.id == record.id } ?: return
            damages.forEach { (member, damage) -> contributions.merge(member, damage, ::maxOf) }
            val hp = (current.maxHp - contributions.values.sum()).coerceAtLeast(0)
            boss =
                when {
                    current.status != RaidBossStatus.ALIVE -> current.copy(hp = hp)
                    record.status != RaidBossStatus.ALIVE ->
                        current.copy(hp = hp, status = record.status, endedAt = record.endedAt ?: now)
                    hp == 0 -> current.copy(hp = 0, status = RaidBossStatus.DEFEATED, endedAt = now)
                    else -> current.copy(hp = hp)
                }
        }
    }

    /**
     * 다시 올리는 동안의 잠금을 잡고 [block]을 돌린다. 다른 스레드가 올리는 중이면 끝나기를 기다린다. [LOAD_WAIT] 안에
     * 잡지 못하면 돌리지 않고 false다. 기록을 읽는 동안 공격을 막지 않도록 상태의 잠금과 따로 둔다.
     */
    fun withLoadLock(block: () -> Unit): Boolean {
        if (!loadLock.tryLock(LOAD_WAIT.toMillis(), TimeUnit.MILLISECONDS)) return false
        try {
            block()
        } finally {
            loadLock.unlock()
        }
        return true
    }

    /** 종료 단계의 시작. 이 뒤로는 공격과 조회를 받지 않는다. 진행 중인 공격은 잠금 때문에 이것보다 먼저 끝난다. */
    fun stopAccepting() = lock.withLock { accepting = false }

    fun resumeAccepting() = lock.withLock { accepting = true }

    /** 모두 비운다. 다음 요청이 기록에서 다시 올린다. 프로세스가 죽었다 다시 뜬 것과 같다. */
    fun clear() =
        lock.withLock {
            boss = null
            contributions.clear()
            dirty.clear()
            cooldownUntil.clear()
        }

    /** 같은 회원이 기다리지 않고 다시 공격하게 한다(테스트). */
    internal fun clearCooldown(memberId: Long) {
        lock.withLock { cooldownUntil.remove(memberId) }
    }

    private fun checkAccepting() {
        if (!accepting) throw RaidUnavailableException()
    }

    private companion object {
        val LOAD_WAIT: Duration = Duration.ofSeconds(2)
    }
}
