package com.ogu.raid.application

import com.ogu.raid.domain.AttackOutcome
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidRedis
import com.ogu.raid.domain.RaidUnavailableException
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/** 계약의 `RaidAttackResult`. 다른 회원의 정보는 없다(research R13). */
data class RaidAttackResult(
    val bossId: Long,
    val hp: Int,
    val maxHp: Int,
    val status: RaidBossStatus,
    val participantCount: Int,
    val myDamage: Int,
    val defeated: Boolean,
    val cooldownMs: Long,
)

/**
 * 보스 공격(006 US1, US3, research R2). 판단은 모두 Redis의 스크립트 하나가 한다. 여기서는 결과를 응답으로 바꾸고,
 * Redis에 보스가 없으면 기록에서 다시 채워 한 번 더 시도하며, 처치면 응답하기 전에 기록에 남긴다.
 */
@Service
class RaidAttackService(
    private val redis: RaidRedis,
    private val loader: RaidLoader,
    private val finisher: RaidFinisher,
    private val properties: RaidProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 쿨다운 안이면 429 `RAID_COOLDOWN`, 보스가 끝났거나 요청의 보스가 지금 보스가 아니면 409 `RAID_BOSS_ENDED`,
     * Redis를 쓸 수 없으면 503 `RAID_UNAVAILABLE`이다. 셋 다 HP와 기여를 바꾸지 않는다.
     */
    fun attack(
        memberId: Long,
        bossId: Long,
    ): RaidAttackResult {
        val now = clock.instant()
        val outcome = attackOnce(bossId, memberId, now)
        if (outcome is AttackOutcome.Accepted) return accepted(bossId, outcome, now)
        throw rejection(outcome)
    }

    /** Redis에 보스가 없으면 기록에서 다시 채우고 한 번 더 시도한다. Redis를 쓸 수 없으면 null이다. */
    private fun attackOnce(
        bossId: Long,
        memberId: Long,
        now: Instant,
    ): AttackOutcome? =
        try {
            val first = redis.attack(bossId, memberId, properties.cooldown, now)
            if (first is AttackOutcome.Reload) {
                loader.ensureLoaded()
                redis.attack(bossId, memberId, properties.cooldown, now)
            } else {
                first
            }
        } catch (e: RaidUnavailableException) {
            log.debug("Redis를 쓸 수 없어 공격을 거절합니다", e)
            null
        }

    private fun rejection(outcome: AttackOutcome?): BusinessException =
        when (outcome) {
            null -> BusinessException(ErrorCode.RAID_UNAVAILABLE)
            is AttackOutcome.Cooldown -> {
                val retryAfter = seconds(outcome)
                BusinessException(ErrorCode.RAID_COOLDOWN, retryAfterSeconds = retryAfter)
            }
            else -> BusinessException(ErrorCode.RAID_BOSS_ENDED)
        }

    private fun accepted(
        bossId: Long,
        outcome: AttackOutcome.Accepted,
        now: Instant,
    ): RaidAttackResult {
        if (outcome.defeated) finishQuietly(bossId, now)
        return RaidAttackResult(
            bossId = bossId,
            hp = outcome.hp.coerceAtLeast(0),
            maxHp = outcome.maxHp,
            status = if (outcome.defeated) RaidBossStatus.DEFEATED else RaidBossStatus.ALIVE,
            participantCount = outcome.participantCount,
            myDamage = outcome.myDamage,
            defeated = outcome.defeated,
            cooldownMs = properties.cooldown.toMillis(),
        )
    }

    /** 기록에 남기지 못해도 공격은 이미 받아들여졌고 Redis의 보스는 끝났다. 다음 옮기기가 다시 마무리한다. */
    @Suppress("TooGenericExceptionCaught") // 어떤 실패든 받아들여진 공격의 응답을 막지 않는다
    private fun finishQuietly(
        bossId: Long,
        now: Instant,
    ) {
        try {
            finisher.finish(bossId, RaidBossStatus.DEFEATED, hp = 0, endedAt = now)
        } catch (e: RuntimeException) {
            log.warn("처치를 기록에 남기지 못했습니다. 다음 옮기기가 다시 합니다: boss={} ({})", bossId, e.javaClass.simpleName)
        }
    }

    /** `Retry-After`는 초 단위라 올림한다. */
    private fun seconds(cooldown: AttackOutcome.Cooldown): Int {
        val millis = cooldown.remaining.toMillis()
        return ((millis + MILLIS - 1) / MILLIS).toInt()
    }

    private companion object {
        const val MILLIS = 1000L
    }
}
