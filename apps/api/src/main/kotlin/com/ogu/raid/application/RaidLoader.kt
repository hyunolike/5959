package com.ogu.raid.application

import com.ogu.raid.domain.RaidBossRepository
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidContributionRepository
import com.ogu.raid.domain.RaidLiveStore
import com.ogu.raid.domain.RaidUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 기록(Postgres)의 가장 최근 보스를 메모리에 올린다(006 research R4, R10, 018 research R5). 비었을 때(처음, 다시 뜬 뒤)와
 * 새 보스가 나왔을 때 같은 길을 쓴다. 여러 요청이 함께 불러도 잠금을 잡은 쪽만 올리고 나머지는 끝나기를 기다린다.
 *
 * 살아 있는 보스의 HP는 기록된 기여의 합으로 다시 계산한다. 기여와 HP는 뒤따라 적히므로, 둘이 어긋나 있으면 기여를 믿는다.
 * 그래야 올린 뒤에도 기여의 합이 줄어든 HP와 같다. `epoch`는 올린 시각의 밀리초라 올릴 때마다 커진다.
 *
 * 기동 뒤 `reconcile-window`(3분) 동안 `reconcile-interval`(30초)마다 기록과 다시 맞춘다(018 research R6). 배포가 겹치면
 * 옛 인스턴스가 끝나며 적은 공격이 이 사이에 기록에 들어온다. 창이 끝나면 스레드도 끝나 DB를 깨우지 않는다.
 */
@Component
class RaidLoader(
    private val bosses: RaidBossRepository,
    private val contributions: RaidContributionRepository,
    private val store: RaidLiveStore,
    private val properties: RaidProperties,
    private val clock: Clock,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    private var executor: ScheduledExecutorService? = null

    fun ensureLoaded() {
        store.withLoadLock(::load)
    }

    /**
     * 메모리의 보스가 기록의 가장 최근 보스와 다르면 올린다. 새 보스가 나왔는데 메모리에 지난 보스가 남아 있을 때를 맞춘다.
     * [latestId]는 부르는 쪽이 이미 읽은 기록의 가장 최근 보스다(기록을 다시 읽지 않는다).
     */
    fun syncWithRecord(latestId: Long) {
        if (store.read()?.id != latestId) ensureLoaded()
    }

    /** 메모리의 보스를 기록과 맞춘다. 올린 보스가 없으면 DB를 읽지 않는다. */
    fun reconcile() {
        val live = store.read() ?: return
        val record = bosses.find(live.id) ?: return
        store.reconcile(record, contributions.all(live.id), clock.instant())
    }

    override fun start() {
        val thread =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "raid-reconcile").apply { isDaemon = true }
            }
        val interval = properties.reconcileInterval.toMillis()
        thread.scheduleWithFixedDelay(::reconcileSafely, interval, interval, TimeUnit.MILLISECONDS)
        // 창이 끝나면 주기 작업을 거둔다. 끝난 실행기는 스레드를 남기지 않는다
        thread.schedule(thread::shutdown, properties.reconcileWindow.toMillis(), TimeUnit.MILLISECONDS)
        executor = thread
    }

    override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    override fun isRunning(): Boolean = executor != null

    /** 한 번의 실패가 다음 주기를 막지 않게 한다. 예외를 밖으로 내면 실행기가 다음 주기를 버린다. */
    @Suppress("TooGenericExceptionCaught")
    private fun reconcileSafely() {
        try {
            reconcile()
        } catch (_: RaidUnavailableException) {
            // 종료 중이다. 남은 것은 RaidFlusher가 적는다
        } catch (e: RuntimeException) {
            log.warn("레이드를 기록과 맞추지 못했습니다: {}", e.javaClass.simpleName)
        }
    }

    private fun load() {
        val recorded = bosses.findLatest() ?: return
        val damages = contributions.all(recorded.id)
        val boss =
            if (recorded.status == RaidBossStatus.ALIVE) {
                recorded.copy(hp = (recorded.maxHp - damages.values.sum()).coerceAtLeast(1))
            } else {
                recorded
            }
        store.load(boss, damages, epoch = clock.millis())
    }
}
