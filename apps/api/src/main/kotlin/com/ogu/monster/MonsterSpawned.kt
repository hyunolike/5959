package com.ogu.monster

/**
 * 몬스터가 생겼다. `MonsterFactory`가 몬스터를 저장한 직후 내고, 알림 모듈이 커밋 뒤 비동기로 받는다(004 research R8).
 * [defaulted]는 24시간 안에 분석하지 못해 기본 몬스터(무기력, 낮음)로 생겼는지다.
 */
data class MonsterSpawned(
    val postId: Long,
    val monsterId: Long,
    val defaulted: Boolean,
)
