package com.ogu.monster

/** monster 모듈 파사드. 다른 모듈은 monsters, monster_hp_log 테이블을 직접 읽지 않는다. */
interface MonsterApi {
    /** 글 ID별 몬스터. 아직 몬스터가 없는 글은 결과에 없다. */
    fun findByPostIds(postIds: Collection<Long>): Map<Long, MonsterView>

    /**
     * [memberId]의 댓글이 이 글의 몬스터 HP에 이미 반영됐는가(댓글 감소는 회원당 글 하나에 한 번, research R5).
     * 몬스터가 아직 없거나 작성자면 false다. 웹의 낙관적 HP 계산(research R6)이 쓴다.
     */
    fun hasCountedComment(
        postId: Long,
        memberId: Long,
    ): Boolean

    /**
     * 이 몬스터의 HP를 실제로 줄인 회원(004 research R9, 명확화 1). 기록이 여럿이어도 한 번이고, 처치 뒤 남긴 응원
     * (`hp_before = hp_after = 0`)만 있는 회원은 빠진다.
     */
    fun damagerIds(monsterId: Long): Set<Long>

    /** [postIds]의 글에 있는 몬스터의 감정, 상태, 생긴 시각(004 research R12). 몬스터가 없는 글은 빠진다. 쿼리 한 번이다. */
    fun statRows(postIds: Collection<Long>): List<MonsterStatRow>

    /**
     * [memberId]가 HP를 실제로 줄인(`hp_after < hp_before`) 몬스터 가운데 처치된 것의 글 ID(004 research R12, US4-AC5).
     * 한 몬스터에 기록이 여럿이어도 한 번이고, 처치 뒤 응원만 한 몬스터는 빠진다. 지운 글도 들어 있으니 부르는 쪽이 뺀다.
     */
    fun defeatedPostIdsDamagedBy(memberId: Long): Set<Long>
}
