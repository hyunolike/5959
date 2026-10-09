package com.ogu.raid

/** 다른 모듈이 레이드의 기록을 읽는 파사드(006 data-model.md). 살아 있는 보스의 실시간 값은 주지 않는다. */
interface RaidApi {
    /** 그 보스를 한 번이라도 공격한 회원. 끝난 보스에 쓴다(Postgres의 최종 기록). */
    fun participantIds(bossId: Long): List<Long>

    /** 회원이 공격에 참여한 보스 가운데 처치된 것의 수. */
    fun defeatedCount(memberId: Long): Int
}
