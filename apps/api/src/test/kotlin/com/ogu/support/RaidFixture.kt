package com.ogu.support

import com.ogu.raid.domain.RaidRedis
import org.awaitility.Awaitility.await
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * 레이드 테스트 도우미(006). 테스트 컨텍스트들이 Postgres와 Redis를 함께 쓰고 다른 컨텍스트의 주기 작업(옮기기, 보스의
 * 생애)도 계속 돈다. 그래서 보스를 지우지 않고, 테스트마다 살아 있는 보스를 물러나게 한 뒤 새 보스를 만든다.
 */
class RaidFixture(
    private val jdbcTemplate: JdbcTemplate,
    private val redis: StringRedisTemplate,
) {
    /** 살아 있는 보스를 물러나게 하고 HP가 [maxHp]인 새 보스를 만든다. Redis는 비워 첫 요청이 기록에서 채우게 한다. */
    fun freshBoss(
        maxHp: Int,
        emotion: String = "LETHARGY",
    ): Long {
        jdbcTemplate.update("update raid_boss set status = 'RETREATED', ended_at = now() where status = 'ALIVE'")
        val bossId =
            jdbcTemplate.queryForObject(
                """
                insert into raid_boss (emotion, max_hp, hp, status, spawned_at)
                values (?, ?, ?, 'ALIVE', now())
                returning id
                """.trimIndent(),
                Long::class.java,
                emotion,
                maxHp,
                maxHp,
            )!!
        redis.delete(RaidRedis.BOSS_KEY)
        return bossId
    }

    /** 다른 테스트와 겹치지 않는 회원 ID. 레이드의 기록은 회원 테이블을 참조하지 않는다. */
    fun memberIds(count: Int): List<Long> = (1..count).map { MEMBER_IDS.incrementAndGet() }

    /** 같은 회원이 기다리지 않고 다시 공격하게 한다. */
    fun clearCooldown(memberId: Long) {
        redis.delete("raid:cd:$memberId")
    }

    fun boss(bossId: Long): Map<String, Any?> = jdbcTemplate.queryForMap("select * from raid_boss where id = ?", bossId)

    fun recordedDamageSum(bossId: Long): Int =
        jdbcTemplate.queryForObject(
            "select coalesce(sum(damage), 0) from raid_contribution where boss_id = ?",
            Int::class.java,
            bossId,
        )!!

    fun recordedDamage(
        bossId: Long,
        memberId: Long,
    ): Int =
        jdbcTemplate.queryForObject(
            "select coalesce(max(damage), 0) from raid_contribution where boss_id = ? and member_id = ?",
            Int::class.java,
            bossId,
            memberId,
        )!!

    fun redisHp(): Int = redis.opsForHash<String, String>().get(RaidRedis.BOSS_KEY, "hp")!!.toInt()

    fun redisDamageSum(bossId: Long): Int =
        redis
            .opsForHash<String, String>()
            .values(RaidRedis.contributionsKey(bossId))
            .sumOf { it.toInt() }

    /** 옮기기는 어느 컨텍스트의 주기 작업이 먼저 할 수도 있다. 기록이 기대한 값이 될 때까지 기다린다. */
    fun awaitRecorded(
        bossId: Long,
        damageSum: Int,
    ) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { recordedDamageSum(bossId) == damageSum }
    }

    fun awaitStatus(
        bossId: Long,
        status: String,
    ) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { boss(bossId)["status"] == status }
    }

    companion object {
        private val MEMBER_IDS = AtomicLong(System.currentTimeMillis())
        val AWAIT_LIMIT: Duration = Duration.ofSeconds(10)
        val POLL: Duration = Duration.ofMillis(50)
    }
}
