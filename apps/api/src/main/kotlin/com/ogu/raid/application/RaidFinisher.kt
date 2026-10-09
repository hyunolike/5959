package com.ogu.raid.application

import com.ogu.raid.RaidBossDefeated
import com.ogu.raid.domain.RaidBossRepository
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidContributionRepository
import com.ogu.raid.domain.RaidRedis
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 끝난 보스를 기록에 남긴다(006 research R5). 모든 기여를 통째로 적고, 보스 행을 살아 있을 때만 끝난 상태로 바꾼다.
 * 조건부 UPDATE가 한 행만 바꾸므로 겹쳐 불려도 처치 이벤트는 한 번만 나간다. 처치를 기록에 적은 뒤에 알리므로
 * Redis가 사라져도 처치된 보스는 처치된 채다.
 */
@Component
class RaidFinisher(
    private val bosses: RaidBossRepository,
    private val contributions: RaidContributionRepository,
    private val redis: RaidRedis,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun finish(
        bossId: Long,
        status: RaidBossStatus,
        hp: Int,
        endedAt: Instant,
    ) {
        val damages = redis.contributions(bossId)
        contributions.upsertAll(bossId, damages, clock.instant().truncatedTo(ChronoUnit.MICROS))
        val ended = bosses.end(bossId, status, hp, damages.size, endedAt.truncatedTo(ChronoUnit.MICROS))
        if (ended && status == RaidBossStatus.DEFEATED) events.publishEvent(RaidBossDefeated(bossId))
        redis.expireContributions(bossId)
    }
}
