package com.ogu.raid.domain

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** `raid_contribution` 저장소(006 data-model.md). 회원이 보스에게 준 피해의 합이고 늘어나기만 한다. */
@Repository
class RaidContributionRepository(
    private val jdbcClient: JdbcClient,
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Redis에서 읽은 기여를 절댓값으로 적는다. 같은 값을 두 번 적거나 더 작은 값이 늦게 와도 결과가 같다(research R3).
     */
    fun upsertAll(
        bossId: Long,
        damages: Map<Long, Int>,
        now: Instant,
    ) {
        if (damages.isEmpty()) return
        val timestamp = Timestamp.from(now)
        val rows = damages.map { (memberId, damage) -> arrayOf<Any>(bossId, memberId, damage, timestamp, timestamp) }
        jdbcTemplate.batchUpdate(UPSERT, rows)
    }

    fun all(bossId: Long): Map<Long, Int> =
        jdbcClient
            .sql("select member_id, damage from raid_contribution where boss_id = :bossId")
            .param("bossId", bossId)
            .query { rs, _ -> rs.getLong("member_id") to rs.getInt("damage") }
            .list()
            .toMap()

    fun damageOf(
        bossId: Long,
        memberId: Long,
    ): Int =
        jdbcClient
            .sql("select damage from raid_contribution where boss_id = :bossId and member_id = :memberId")
            .param("bossId", bossId)
            .param("memberId", memberId)
            .query(Int::class.java)
            .optional()
            .orElse(0)

    fun participantIds(bossId: Long): List<Long> =
        jdbcClient
            .sql("select member_id from raid_contribution where boss_id = :bossId order by member_id")
            .param("bossId", bossId)
            .query { rs, _ -> rs.getLong("member_id") }
            .list()

    /** 회원이 공격에 참여한 보스 가운데 처치된 것의 수(research R12). */
    fun defeatedCount(memberId: Long): Int =
        jdbcClient
            .sql(
                """
                select count(*)
                from raid_contribution c
                    join raid_boss b on b.id = c.boss_id and b.status = 'DEFEATED'
                where c.member_id = :memberId
                """.trimIndent(),
            ).param("memberId", memberId)
            .query(Int::class.java)
            .single()

    private companion object {
        val UPSERT =
            """
            insert into raid_contribution (boss_id, member_id, damage, first_attack_at, updated_at)
            values (?, ?, ?, ?, ?)
            on conflict (boss_id, member_id) do update
            set damage = greatest(raid_contribution.damage, excluded.damage), updated_at = excluded.updated_at
            """.trimIndent()
    }
}
