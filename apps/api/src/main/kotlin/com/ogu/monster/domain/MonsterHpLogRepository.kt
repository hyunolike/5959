package com.ogu.monster.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp

/** `monster_hp_log` 저장소. 기록은 글 잠금(PostLock) 안에서만 쓴다(data-model.md 반영 규칙 3). */
@Repository
class MonsterHpLogRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 기록을 남긴다. 같은 유일 키의 기록이 이미 있으면 아무것도 하지 않고 null을 돌려준다(`ON CONFLICT DO NOTHING`).
     * 변경 전후 HP는 [recordHp]로 채운다(HP를 줄이기 전에는 모른다).
     */
    fun insertIfAbsent(log: MonsterHpLog): Long? =
        jdbcClient
            .sql(
                """
                insert into monster_hp_log (monster_id, member_id, action, target_id, hp_delta, hp_before, hp_after,
                                            retroactive, created_at)
                values (:monsterId, :memberId, :action, :targetId, :hpDelta, 0, 0, :retroactive, :createdAt)
                on conflict on constraint monster_hp_log_attack_key do nothing
                returning id
                """.trimIndent(),
            ).param("monsterId", log.monsterId)
            .param("memberId", log.memberId)
            .param("action", log.action.name)
            .param("targetId", log.targetId)
            .param("hpDelta", log.hpDelta)
            .param("retroactive", log.retroactive)
            .param("createdAt", Timestamp.from(log.createdAt))
            .query(Long::class.java)
            .optional()
            .orElse(null)

    fun recordHp(
        logId: Long,
        change: HpChange,
    ) {
        jdbcClient
            .sql("update monster_hp_log set hp_before = :hpBefore, hp_after = :hpAfter where id = :id")
            .param("hpBefore", change.hpBefore)
            .param("hpAfter", change.hpAfter)
            .param("id", logId)
            .update()
    }

    /** 이 글의 몬스터에 [memberId]의 댓글 감소 기록이 있는가. 몬스터가 없으면 false. */
    fun existsComment(
        postId: Long,
        memberId: Long,
    ): Boolean =
        jdbcClient
            .sql(
                """
                select exists (
                    select 1 from monster_hp_log l join monsters m on m.id = l.monster_id
                    where m.post_id = :postId and l.member_id = :memberId and l.action = 'COMMENT'
                )
                """.trimIndent(),
            ).param("postId", postId)
            .param("memberId", memberId)
            .query(Boolean::class.java)
            .single()

    /**
     * HP를 실제로 줄인 회원(004 research R9). 한 회원의 기록이 여럿이어도 한 번이고, 처치 뒤 응원
     * (`hp_before = hp_after = 0`)은 빠진다. 유일 키 `(monster_id, member_id, action, target_id)`의 앞부분으로 찾는다.
     */
    fun damagerIds(monsterId: Long): Set<Long> =
        jdbcClient
            .sql("select distinct member_id from monster_hp_log where monster_id = :monsterId and hp_after < hp_before")
            .param("monsterId", monsterId)
            .query(Long::class.java)
            .list()
            .filterNotNull()
            .toSet()
}
