package com.ogu.raid

/**
 * 보스가 처치됐다(006 research R5). 보스 행을 처치됨으로 바꾼 트랜잭션에서 한 번만 나간다. 커밋 뒤 notification 모듈이
 * 참여한 회원에게 알린다. 참여자는 싣지 않는다([RaidApi.participantIds]로 읽는다).
 */
data class RaidBossDefeated(
    val bossId: Long,
)
