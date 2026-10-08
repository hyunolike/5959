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
}
