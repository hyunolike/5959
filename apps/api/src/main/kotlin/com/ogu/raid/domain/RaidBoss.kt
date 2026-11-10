package com.ogu.raid.domain

import com.ogu.emotion.EmotionType
import java.time.Instant

enum class RaidBossStatus {
    ALIVE,
    DEFEATED,
    RETREATED,
}

/**
 * 보스 하나의 상태. 메모리([RaidLiveStore])에서 읽었으면 지금 값이고, Postgres에서 읽었으면 마지막으로 옮겨 적은 값이다.
 * [epoch]는 메모리에 기록에서 다시 올린 시각(ms)이라 올릴 때마다 커진다. Postgres에서 읽은 것은 0이다.
 */
data class RaidBoss(
    val id: Long,
    val emotion: EmotionType,
    val maxHp: Int,
    val hp: Int,
    val status: RaidBossStatus,
    val participantCount: Int,
    val spawnedAt: Instant,
    val endedAt: Instant?,
    val epoch: Long = 0,
)
