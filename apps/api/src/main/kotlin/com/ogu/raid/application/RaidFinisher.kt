package com.ogu.raid.application

import com.ogu.raid.RaidBossDefeated
import com.ogu.raid.domain.RaidBossRepository
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidContributionRepository
import com.ogu.raid.domain.RaidLiveStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 끝난 보스를 기록에 남긴다(006 research R5). 모든 기여를 통째로 적고, 보스 행을 살아 있을 때만 끝난 상태로 바꾼다.
 * 조건부 UPDATE가 한 행만 바꾸므로 겹쳐 불려도 처치 이벤트는 한 번만 나간다. 처치를 기록에 적은 뒤에 알리므로
 * 서버가 다시 떠도 처치된 보스는 처치된 채다. 끝냈으면 커밋 뒤에 보스 교체 타이머를 다음 날 0시로 건다(018 research R3).
 */
@Component
@Suppress("LongParameterList") // 끝낸 뒤 보스 교체 타이머를 다시 걸려고 하나가 늘었다(018 T019)
class RaidFinisher(
    private val bosses: RaidBossRepository,
    private val contributions: RaidContributionRepository,
    private val store: RaidLiveStore,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    /** [retreat]가 [finish]의 트랜잭션을 거쳐 부르도록 자기 자신의 프록시를 받는다. */
    @param:Lazy private val self: RaidFinisher,
    /** 주기 작업을 끈 테스트에서는 없다. */
    private val lifecycle: ObjectProvider<RaidLifecycleScheduler>,
) {
    /**
     * 살아 있는 보스를 물러나게 한다(US5-AC5). 메모리에서 먼저 끝내 공격을 막고 기록에 남긴다. 그 사이 처치됐으면
     * 아무것도 하지 않는다. 물러난 보스는 처치 이벤트를 내지 않는다.
     */
    fun retreat(
        bossId: Long,
        now: Instant,
    ) {
        if (!store.end(bossId, RaidBossStatus.RETREATED, now)) return
        val hp = store.read()?.takeIf { it.id == bossId }?.hp ?: return
        self.finish(bossId, RaidBossStatus.RETREATED, hp, now)
    }

    @Transactional
    fun finish(
        bossId: Long,
        status: RaidBossStatus,
        hp: Int,
        endedAt: Instant,
    ) {
        val damages = store.contributions(bossId)
        contributions.upsertAll(bossId, damages, clock.instant().truncatedTo(ChronoUnit.MICROS))
        val ended = bosses.end(bossId, status, hp, damages.size, endedAt.truncatedTo(ChronoUnit.MICROS))
        if (ended && status == RaidBossStatus.DEFEATED) events.publishEvent(RaidBossDefeated(bossId))
        if (ended) lifecycle.ifAvailable { it.bossEnded(endedAt) }
    }
}
