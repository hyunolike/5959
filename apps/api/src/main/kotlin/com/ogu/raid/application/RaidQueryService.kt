package com.ogu.raid.application

import com.ogu.emotion.EmotionType
import com.ogu.raid.domain.RaidBoss
import com.ogu.raid.domain.RaidBossRepository
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidContributionRepository
import com.ogu.raid.domain.RaidRedis
import com.ogu.raid.domain.RaidUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/** 계약의 `RaidBoss`. */
data class RaidBossView(
    val bossId: Long,
    val emotion: EmotionType,
    val maxHp: Int,
    val hp: Int,
    val status: RaidBossStatus,
    val participantCount: Int,
    val spawnedAt: Instant,
    val endedAt: Instant?,
)

/** 계약의 `RaidState`. [boss]는 살아 있는 보스이거나, 없으면 가장 최근에 끝난 보스다. */
data class RaidState(
    val boss: RaidBossView?,
    val myDamage: Int,
    val nextBossAt: Instant?,
    val available: Boolean,
    val epoch: Long,
)

/**
 * 지금의 레이드(006 US1-AC1, US4-AC3, US5-AC3). Redis의 값을 주고, Redis를 쓸 수 없으면 마지막으로 기록된 값과
 * `available = false`를 준다(research R4). 요청한 회원 자신의 기여만 싣는다(R13).
 */
@Service
class RaidQueryService(
    private val redis: RaidRedis,
    private val loader: RaidLoader,
    private val bosses: RaidBossRepository,
    private val contributions: RaidContributionRepository,
    private val properties: RaidProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun state(memberId: Long): RaidState =
        try {
            val boss = current()
            stateOf(boss, boss?.let { redis.damageOf(it.id, memberId) } ?: 0, available = true)
        } catch (e: RaidUnavailableException) {
            log.debug("Redis를 쓸 수 없어 마지막 기록으로 답합니다", e)
            val boss = bosses.findLatest()
            stateOf(boss, boss?.let { contributions.damageOf(it.id, memberId) } ?: 0, available = false)
        }

    /** Redis의 보스. 비어 있으면 기록에서 한 번 채운다. 보스가 한 번도 없었으면 null이다. */
    fun current(): RaidBoss? {
        redis.read()?.let { return it }
        loader.ensureLoaded()
        return redis.read()
    }

    private fun stateOf(
        boss: RaidBoss?,
        myDamage: Int,
        available: Boolean,
    ): RaidState =
        RaidState(
            boss = boss?.toView(),
            myDamage = myDamage,
            nextBossAt = boss?.endedAt?.let(::nextBossAt),
            available = available,
            epoch = boss?.epoch ?: 0,
        )

    /** 보스가 끝난 다음 날 0시(설정한 시간대). 23시 59분에 끝나도 다음 날 0시다(US5-AC3). */
    private fun nextBossAt(endedAt: Instant): Instant =
        endedAt
            .atZone(properties.zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(properties.zone)
            .toInstant()
}

fun RaidBoss.toView() =
    RaidBossView(
        bossId = id,
        emotion = emotion,
        maxHp = maxHp,
        hp = hp,
        status = status,
        participantCount = participantCount,
        spawnedAt = spawnedAt,
        endedAt = endedAt,
    )
