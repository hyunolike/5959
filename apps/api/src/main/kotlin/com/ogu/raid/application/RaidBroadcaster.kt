package com.ogu.raid.application

import com.ogu.raid.domain.RaidBoss
import com.ogu.raid.domain.RaidBossRepository
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidUnavailableException
import com.ogu.shared.realtime.TopicBroadcaster
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** 계약의 `RaidLive`. 모든 연결에 같은 내용이 가므로 회원의 기여는 싣지 않는다(research R13). */
data class RaidLive(
    val bossId: Long?,
    val hp: Int,
    val maxHp: Int,
    val status: RaidBossStatus,
    val participantCount: Int,
    val available: Boolean,
    val epoch: Long,
)

/**
 * 레이드 상태를 실시간 스트림으로 내보낸다(006 US2, research R6, R7). 주기(250ms)마다 `raid` 주제를 듣는 연결이 있으면
 * Redis에서 보스를 한 번 읽고, 지난번에 보낸 것과 다르거나 새 연결이 붙었을 때만 보낸다. 공격이 아무리 몰려도 한 화면이
 * 받는 것은 초당 네 번이고, 마지막에는 정확한 값이 간다. 듣는 연결이 없으면 읽지 않는다.
 *
 * Redis를 읽지 못하면 마지막 값(없으면 기록)에 `available = false`를 실어 보낸다.
 */
@Component
class RaidBroadcaster(
    private val broadcaster: TopicBroadcaster,
    private val queryService: RaidQueryService,
    private val bosses: RaidBossRepository,
    private val jsonMapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var lastSent: RaidLive? = null

    @Volatile
    private var lastJoins = 0L

    @Scheduled(fixedDelayString = "\${ogu.raid.broadcast-interval:250ms}")
    fun tick() {
        if (broadcaster.listenerCount(TOPIC) == 0) return
        val joins = broadcaster.joinCount(TOPIC)
        val live = read()
        if (live != null && (live != lastSent || joins != lastJoins)) {
            broadcaster.broadcast(TOPIC, EVENT, jsonMapper.writeValueAsString(live))
            lastSent = live
            lastJoins = joins
        }
    }

    /** 지금의 보스. 보스가 한 번도 없었으면 null이다. */
    private fun read(): RaidLive? =
        try {
            queryService.current()?.toLive(available = true)
        } catch (e: RaidUnavailableException) {
            log.debug("Redis를 쓸 수 없어 마지막 값으로 내보냅니다", e)
            lastSent?.copy(available = false) ?: bosses.findLatest()?.toLive(available = false)
        }

    private fun RaidBoss.toLive(available: Boolean) =
        RaidLive(
            bossId = id,
            hp = hp,
            maxHp = maxHp,
            status = status,
            participantCount = participantCount,
            available = available,
            epoch = epoch,
        )

    companion object {
        const val TOPIC = "raid"
        const val EVENT = "raid"
    }
}
