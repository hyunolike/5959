package com.ogu.raid.application

import com.ogu.raid.domain.RaidBossRepository
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidContributionRepository
import com.ogu.raid.domain.RaidFlush
import com.ogu.raid.domain.RaidLiveStore
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 메모리의 레이드 상태를 Postgres로 옮긴다(006 research R3, 018 research R5). 주기마다 아직 적지 않은 회원의 기여와 지금
 * HP를 꺼내 절댓값으로 적는다. 값이 한쪽으로만 움직이므로 두 번 적거나 배포가 겹친 두 인스턴스가 함께 적어도 결과가 같다.
 *
 * - 적다 실패하면 꺼낸 회원을 되돌려 다음 주기가 다시 적는다.
 * - 메모리의 보스는 끝났는데 기록은 살아 있으면(처치 마무리가 실패했을 때) 여기서 다시 마무리한다(R5).
 * - 정상 종료([stop])에서는 공격을 더 받지 않게 한 뒤 남은 것을 모두 적는다(US3-AC5). 갑자기 죽으면 마지막 주기 뒤의
 *   공격만 잃고, 다시 올릴 때 기록된 기여로 HP를 다시 세므로 숫자는 서로 맞는다(US3-AC8).
 *
 * 단계는 기본값(가장 늦은 단계)이라 웹 서버의 우아한 종료보다 먼저 멈춘다. 그 사이 들어온 공격은 503이다.
 */
@Component
class RaidFlusher(
    private val store: RaidLiveStore,
    private val bosses: RaidBossRepository,
    private val contributions: RaidContributionRepository,
    private val finisher: RaidFinisher,
    private val transaction: TransactionTemplate,
    private val clock: Clock,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 주기 작업과 종료 단계가 겹쳐도 한 번에 하나만 적는다. 종료 단계가 진행 중인 쓰기를 기다리게 한다. */
    private val flushing = ReentrantLock()

    /** 기록까지 끝난 것을 확인한 보스. 끝난 보스가 메모리에 남아 있는 동안 주기마다 기록을 다시 읽지 않게 한다. */
    @Volatile
    private var settledBossId: Long? = null

    @Volatile
    private var running = false

    @Scheduled(fixedDelayString = "\${ogu.raid.flush-interval:1s}")
    fun flush() {
        drain()
    }

    override fun start() {
        store.resumeAccepting()
        running = true
    }

    /** 공격을 더 받지 않고 남은 것을 모두 적는다. DB에 쓰지 못하면 남은 공격은 잃는다. */
    override fun stop() {
        store.stopAccepting()
        if (!drain()) log.warn("레이드 기여를 다 적지 못하고 끝납니다")
        running = false
    }

    override fun isRunning(): Boolean = running

    /** 바뀐 회원을 모두 적었으면 true다. DB에 쓰지 못했으면 되돌려 두고 false다. */
    private fun drain(): Boolean =
        flushing.withLock {
            try {
                do {
                    val batch = store.flush(BATCH_SIZE) ?: return true
                    write(batch)
                    settle(batch)
                } while (batch.damages.size >= BATCH_SIZE)
                true
            } catch (e: DataAccessException) {
                log.debug("DB에 쓰지 못해 레이드 옮기기를 미룹니다: {}", e.javaClass.simpleName)
                false
            }
        }

    /**
     * 바뀐 회원이 없으면 DB를 열지 않는다(018 research R5). HP와 참여자 수는 공격으로만 바뀌고 공격한 회원은 바뀐 회원에
     * 들어가므로 적을 것이 없다. 끝난 보스는 마무리([settle])를 확인하기 전까지 한 번 더 적는다.
     */
    private fun write(batch: RaidFlush) {
        if (batch.damages.isEmpty() && (batch.status == RaidBossStatus.ALIVE || batch.bossId == settledBossId)) return
        try {
            transaction.executeWithoutResult {
                contributions.upsertAll(batch.bossId, batch.damages, clock.instant().truncatedTo(ChronoUnit.MICROS))
                bosses.snapshot(batch.bossId, batch.hp, batch.participantCount)
            }
        } catch (e: DataAccessException) {
            store.markDirty(batch.bossId, batch.damages.keys)
            log.warn("레이드 기여를 적지 못해 다음 주기로 미룹니다: boss={} ({})", batch.bossId, e.javaClass.simpleName)
            throw e
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
