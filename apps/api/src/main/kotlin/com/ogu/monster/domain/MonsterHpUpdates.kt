package com.ogu.monster.domain

import com.ogu.monster.MonsterStatus
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Instant

/** [MonsterRepository]에 붙는 SQL 조각. JPA 엔티티를 거치지 않고 HP를 원자적으로 바꾼다. */
interface MonsterHpUpdates {
    /** 글의 몬스터 ID. 아직 없으면 null. */
    fun findIdByPostId(postId: Long): Long?

    /**
     * HP를 [delta]만큼 줄이되 0에서 멈추고, 0이면 처치 상태로 바꾼다(data-model.md 반영 규칙 4). 처음 0이 될 때만
     * `defeated_at`을 [now]로 채운다. 변경 전후 HP와 상태를 한 문장으로 돌려준다.
     */
    fun decrementHp(
        monsterId: Long,
        delta: Int,
        now: Instant,
    ): HpChange
}

class MonsterHpUpdatesImpl(
    private val jdbcClient: JdbcClient,
) : MonsterHpUpdates {
    override fun findIdByPostId(postId: Long): Long? =
        jdbcClient
            .sql("select id from monsters where post_id = :postId")
            .param("postId", postId)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    override fun decrementHp(
        monsterId: Long,
        delta: Int,
        now: Instant,
    ): HpChange =
        jdbcClient
            .sql(
                """
                update monsters m
                set hp = greatest(m.hp - :delta, 0),
                    status = case when m.hp - :delta <= 0 then 'DEFEATED' else 'ALIVE' end,
                    defeated_at = case when m.hp > 0 and m.hp - :delta <= 0 then :now else m.defeated_at end
                from (select id, hp from monsters where id = :id for update) prev
                where m.id = prev.id
                returning prev.hp as hp_before, m.hp as hp_after, m.status
                """.trimIndent(),
            ).param("delta", delta)
            .param("now", Timestamp.from(now))
            .param("id", monsterId)
            .query { rs, _ ->
                HpChange(rs.getInt("hp_before"), rs.getInt("hp_after"), MonsterStatus.valueOf(rs.getString("status")))
            }.single()
}
