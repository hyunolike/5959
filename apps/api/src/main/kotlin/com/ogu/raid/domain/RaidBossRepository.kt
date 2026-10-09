package com.ogu.raid.domain

import com.ogu.emotion.EmotionType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/** `raid_boss` 저장소(006 data-model.md). 살아 있는 보스가 하나라는 것은 부분 유일 인덱스가 지킨다. */
@Repository
class RaidBossRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 새 보스를 만든다. 살아 있는 보스가 이미 있으면 아무것도 하지 않고 null을 돌려준다(US5-AC6). */
    fun insertAlive(
        emotion: EmotionType,
        maxHp: Int,
        now: Instant,
    ): Long? =
        jdbcClient
            .sql(
                """
                insert into raid_boss (emotion, max_hp, hp, status, spawned_at)
                values (:emotion, :maxHp, :maxHp, 'ALIVE', :now)
                on conflict do nothing
                returning id
                """.trimIndent(),
            ).param("emotion", emotion.name)
            .param("maxHp", maxHp)
            .param("now", Timestamp.from(now))
            .query(Long::class.java)
            .optional()
            .orElse(null)

    /** 가장 최근 보스. 살아 있는 보스가 있으면 그것이고, 없으면 마지막에 끝난 보스다. 한 번도 없었으면 null이다. */
    fun findLatest(): RaidBoss? =
        jdbcClient
            .sql("$SELECT order by id desc limit 1")
            .query { rs, _ -> rs.toBoss() }
            .optional()
            .orElse(null)

    fun find(id: Long): RaidBoss? =
        jdbcClient
            .sql("$SELECT where id = :id")
            .param("id", id)
            .query { rs, _ -> rs.toBoss() }
            .optional()
            .orElse(null)

    /** 살아 있는 동안 Redis의 값을 옮겨 적는다. HP는 줄어들기만, 참여자 수는 늘어나기만 한다(research R3). */
    fun snapshot(
        id: Long,
        hp: Int,
        participantCount: Int,
    ) {
        jdbcClient
            .sql(
                """
                update raid_boss
                set hp = least(hp, :hp), participant_count = greatest(participant_count, :participants)
                where id = :id and status = 'ALIVE'
                """.trimIndent(),
            ).param("hp", hp)
            .param("participants", participantCount)
            .param("id", id)
            .update()
    }

    /** 살아 있는 보스를 끝낸다. 이미 끝났으면 false다. 겹친 요청 가운데 하나만 true를 받는다(research R5). */
    fun end(
        id: Long,
        status: RaidBossStatus,
        hp: Int,
        participantCount: Int,
        endedAt: Instant,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update raid_boss
                set status = :status, hp = least(hp, :hp), participant_count = :participants, ended_at = :endedAt
                where id = :id and status = 'ALIVE'
                """.trimIndent(),
            ).param("status", status.name)
            .param("hp", hp)
            .param("participants", participantCount)
            .param("endedAt", Timestamp.from(endedAt))
            .param("id", id)
            .update() == 1

    private fun ResultSet.toBoss() =
        RaidBoss(
            id = getLong("id"),
            emotion = EmotionType.valueOf(getString("emotion")),
            maxHp = getInt("max_hp"),
            hp = getInt("hp"),
            status = RaidBossStatus.valueOf(getString("status")),
            participantCount = getInt("participant_count"),
            spawnedAt = getTimestamp("spawned_at").toInstant(),
            endedAt = getTimestamp("ended_at")?.toInstant(),
        )

    private companion object {
        const val SELECT =
            "select id, emotion, max_hp, hp, status, participant_count, spawned_at, ended_at from raid_boss"
    }
}
