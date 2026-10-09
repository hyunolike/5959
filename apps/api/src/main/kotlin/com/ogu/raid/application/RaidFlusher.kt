package com.ogu.raid.application

import com.ogu.raid.domain.RaidBossRepository
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidContributionRepository
import com.ogu.raid.domain.RaidFlush
import com.ogu.raid.domain.RaidRedis
import com.ogu.raid.domain.RaidUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Redis의 레이드 상태를 Postgres로 옮긴다(006 research R3). 주기마다 아직 적지 않은 회원의 기여와 지금 HP를 꺼내
 * 절댓값으로 적는다. 값이 한쪽으로만 움직이므로 두 번 적거나 인스턴스 여럿이 함께 옮겨도 결과가 같다.
 *
 * - 적다 실패하면 꺼낸 회원을 되돌려 다음 주기가 다시 적는다.
 * - Redis의 보스는 끝났는데 기록은 살아 있으면(처치 마무리가 실패했을 때) 여기서 다시 마무리한다(R5).
 * - Redis를 쓸 수 없으면 조용히 건너뛴다. 돌아오면 이어서 한다.
 */
@Component
class RaidFlusher(
    private val redis: RaidRedis,
    private val bosses: RaidBossRepository,
    private val contributions: RaidContributionRepository,
    private val finisher: RaidFinisher,
    private val transaction: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 기록까지 끝난 것을 확인한 보스. 끝난 보스가 Redis에 남아 있는 동안 주기마다 기록을 다시 읽지 않게 한다. */
    @Volatile
    private var settledBossId: Long? = null

    @Scheduled(fixedDelayString = "\${ogu.raid.flush-interval:1s}")
    fun flush() {
        try {
            do {
                val batch = redis.flush(BATCH_SIZE) ?: return
                store(batch)
                settle(batch)
            } while (batch.damages.size >= BATCH_SIZE)
        } catch (e: RaidUnavailableException) {
            log.debug("Redis를 쓸 수 없어 레이드 옮기기를 건너뜁니다: {}", e.cause?.javaClass?.simpleName)
        }
    }

    private fun store(batch: RaidFlush) {
        if (batch.damages.isEmpty() && batch.bossId == settledBossId) return
        try {
            transaction.executeWithoutResult {
                contributions.upsertAll(batch.bossId, batch.damages, clock.instant().truncatedTo(ChronoUnit.MICROS))
                bosses.snapshot(batch.bossId, batch.hp, batch.participantCount)
            }
        } catch (e: DataAccessException) {
            redis.markDirty(batch.bossId, batch.damages.keys)
            log.warn("레이드 기여를 적지 못해 다음 주기로 미룹니다: boss={} ({})", batch.bossId, e.javaClass.simpleName)
            throw RaidUnavailableException(e)
        }
    }

    private fun settle(batch: RaidFlush) {
        if (batch.status == RaidBossStatus.ALIVE || batch.bossId == settledBossId) return
        if (bosses.find(batch.bossId)?.status == RaidBossStatus.ALIVE) {
            finisher.finish(batch.bossId, batch.status, batch.hp, batch.endedAt ?: clock.instant())
        }
        settledBossId = batch.bossId
    }

    private companion object {
        const val BATCH_SIZE = 500
    }
}
