package com.ogu.monster

import com.ogu.emotion.EmotionType

/** 다른 모듈에 보여 주는 몬스터 상태. 계약의 `MonsterView`와 같은 모양이다. */
data class MonsterView(
    val emotion: EmotionType,
    val hp: Int,
    val maxHp: Int,
    val status: MonsterStatus,
)
