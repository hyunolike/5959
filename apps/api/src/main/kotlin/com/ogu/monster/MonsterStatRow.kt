package com.ogu.monster

import com.ogu.emotion.EmotionType
import java.time.Instant

/** 감정 통계용 몬스터 한 행(004 research R12). [createdAt]은 몬스터가 생긴 시각이다. */
data class MonsterStatRow(
    val postId: Long,
    val emotion: EmotionType,
    val status: MonsterStatus,
    val createdAt: Instant,
)
