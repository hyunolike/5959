package com.ogu.monster

import com.ogu.emotion.EmotionType
import java.time.Instant

/**
 * 도감의 카드가 될 처치된 몬스터 한 행(010 research R2). [damageBy]는 묻는 회원이 줄인 HP의 합이고, [damagerCount]는
 * HP를 실제로 줄인 회원 수다. 누가 줄였는지는 싣지 않는다.
 */
data class MonsterCardRow(
    val monsterId: Long,
    val postId: Long,
    val emotion: EmotionType,
    val maxHp: Int,
    val defeatedAt: Instant,
    val damageBy: Int,
    val damagerCount: Int,
)
