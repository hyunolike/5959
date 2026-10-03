package com.ogu.monster

/** monster 모듈 파사드. 다른 모듈은 monsters, monster_hp_log 테이블을 직접 읽지 않는다. */
interface MonsterApi {
    /** 글 ID별 몬스터. 아직 몬스터가 없는 글은 결과에 없다. */
    fun findByPostIds(postIds: Collection<Long>): Map<Long, MonsterView>
}
