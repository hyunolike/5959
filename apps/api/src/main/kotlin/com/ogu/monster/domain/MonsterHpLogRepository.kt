package com.ogu.monster.domain

import com.ogu.emotion.EmotionType
import com.ogu.monster.MonsterCardRow
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

    /**
     * [memberId]가 HP를 실제로 줄인 처치된 몬스터의 글 ID(004 research R12). 조건은 [damagerIds]와 같고, 인덱스
     * monster_hp_log_member_monster_idx로 찾는다.
     */
    fun defeatedPostIds(memberId: Long): Set<Long> =
        jdbcClient
            .sql(
                """
                select distinct m.post_id
                from monster_hp_log l join monsters m on m.id = l.monster_id
                where l.member_id = :memberId and l.hp_after < l.hp_before and m.status = 'DEFEATED'
                """.trimIndent(),
            ).param("memberId", memberId)
            .query(Long::class.java)
            .list()
            .filterNotNull()
            .toSet()

    /**
     * 도감의 카드가 될 처치된 몬스터(010 research R2, R3): 글이 [ownPostIds]에 있거나, [memberId]의 기록 가운데 HP를
     * 실제로 줄인 것(`hp_after < hp_before`)이 있는 몬스터다. 함께 읽는 것은 그 회원이 줄인 HP의 합과 HP를 줄인 회원 수다.
     * 처치된 뒤의 응원(`hp_before = hp_after = 0`)은 어느 쪽에도 들지 않는다.
     */
    fun cardRows(
        memberId: Long,
        ownPostIds: Set<Long>,
    ): List<MonsterCardRow> {
        val owned = if (ownPostIds.isEmpty()) "false" else "m.post_id in (:ownPostIds)"
        val spec =
            jdbcClient
                .sql(
                    """
                    with picked as (
                        select m.id from monsters m
                        where m.status = 'DEFEATED' and m.defeated_at is not null and $owned
                        union
                        select l.monster_id from monster_hp_log l join monsters m on m.id = l.monster_id
                        where l.member_id = :memberId and l.hp_after < l.hp_before
                          and m.status = 'DEFEATED' and m.defeated_at is not null
                    )
                    select m.id, m.post_id, m.emotion, m.max_hp, m.defeated_at,
                           coalesce(sum(l.hp_before - l.hp_after) filter (where l.member_id = :memberId), 0) as damage_by,
                           count(distinct l.member_id) as damager_count
                    from picked
                    join monsters m on m.id = picked.id
                    -- 처치된 뒤의 응원(0에서 0)은 읽지 않는다. 공감이 아주 많은 글에서 읽는 행이 준다
                    left join monster_hp_log l on l.monster_id = m.id and l.hp_after < l.hp_before
                    group by m.id
                    """.trimIndent(),
                ).param("memberId", memberId)
        val bound = if (ownPostIds.isEmpty()) spec else spec.param("ownPostIds", ownPostIds)
        return bound
            .query { rs, _ ->
                MonsterCardRow(
                    monsterId = rs.getLong("id"),
                    postId = rs.getLong("post_id"),
                    emotion = EmotionType.valueOf(rs.getString("emotion")),
                    maxHp = rs.getInt("max_hp"),
                    defeatedAt = rs.getTimestamp("defeated_at").toInstant(),
                    damageBy = rs.getInt("damage_by"),
                    damagerCount = rs.getInt("damager_count"),
                )
            }.list()
    }
}
