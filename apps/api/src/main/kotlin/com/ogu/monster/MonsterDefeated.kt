package com.ogu.monster

/** 몬스터가 처치됐다(HP 0). 커밋 뒤 비동기로 알린다(M3 알림이 구독). */
data class MonsterDefeated(
    val postId: Long,
    val monsterId: Long,
)
