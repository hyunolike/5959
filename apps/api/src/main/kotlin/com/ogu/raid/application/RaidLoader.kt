package com.ogu.raid.application

import com.ogu.raid.domain.RaidBossRepository
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidContributionRepository
import com.ogu.raid.domain.RaidRedis
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 기록(Postgres)의 가장 최근 보스를 Redis에 올린다(006 research R4, R10). Redis가 비었을 때(처음, 다시 뜬 뒤)와 새 보스가
 * 나왔을 때 같은 길을 쓴다. 인스턴스 여럿이 함께 불러도 잠금을 잡은 쪽만 올리고 나머지는 끝나기를 기다린다.
 *
 * 살아 있는 보스의 HP는 기록된 기여의 합으로 다시 계산한다. 기여와 HP는 뒤따라 적히므로, 둘이 어긋나 있으면 기여를 믿는다.
 * 그래야 올린 뒤에도 기여의 합이 줄어든 HP와 같다.
 */
@Component
class RaidLoader(
    private val bosses: RaidBossRepository,
    private val contributions: RaidContributionRepository,
    private val redis: RaidRedis,
    private val clock: Clock,
) {
    fun ensureLoaded() {
        repeat(LOCK_ATTEMPTS) {
            if (redis.withLoadLock(::load)) return
            Thread.sleep(LOCK_WAIT_MILLIS)
        }
    }

    /**
     * Redis의 보스가 기록의 가장 최근 보스와 다르면 올린다. 새 보스가 나왔는데 Redis에 지난 보스가 남아 있을 때를 맞춘다.
     */
    fun syncWithRecord() {
        val latestId = bosses.findLatest()?.id ?: return
        if (redis.read()?.id != latestId) ensureLoaded()
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
        redis.load(boss, damages, epoch = clock.millis())
    }

    private companion object {
        const val LOCK_ATTEMPTS = 40
        const val LOCK_WAIT_MILLIS = 50L
    }
}
