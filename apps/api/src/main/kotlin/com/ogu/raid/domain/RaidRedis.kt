package com.ogu.raid.domain

import com.ogu.emotion.EmotionType
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** Redis를 쓸 수 없어 레이드를 받을 수 없다(006 research R4). 공격은 503, 조회는 마지막 기록으로 답한다. */
class RaidUnavailableException(
    cause: Throwable,
) : RuntimeException("레이드 상태를 읽거나 쓸 수 없습니다", cause)

/** 공격 스크립트의 결과(006 data-model.md "공격 스크립트의 결과"). */
sealed interface AttackOutcome {
    /** Redis에 보스가 없거나 지난 보스가 남아 있다. 기록에서 다시 채우고 한 번 더 시도한다. */
    data object Reload : AttackOutcome

    data object Ended : AttackOutcome

    data class Cooldown(
        val remaining: Duration,
    ) : AttackOutcome

    data class Accepted(
        val hp: Int,
        val maxHp: Int,
        val myDamage: Int,
        val participantCount: Int,
        val defeated: Boolean,
    ) : AttackOutcome
}

/** 옮길 것 한 묶음. [damages]는 아직 Postgres에 적지 않은 회원의 지금 기여다. */
data class RaidFlush(
    val bossId: Long,
    val hp: Int,
    val status: RaidBossStatus,
    val participantCount: Int,
    val endedAt: Instant?,
    val damages: Map<Long, Int>,
)

/**
 * 레이드의 Redis 상태(006 research R2~R5). 키는 모두 `raid:` 아래에 있다. 공격, 옮기기, 끝내기, 올리기는 각각 Lua
 * 스크립트 하나라 그 안의 단계가 다른 요청과 섞이지 않는다. Redis를 쓸 수 없으면 [RaidUnavailableException]을 던진다.
 */
@Component
class RaidRedis(
    private val redis: StringRedisTemplate,
) {
    private val attackScript = listScript("raid-attack")
    private val flushScript = listScript("raid-flush")
    private val readScript = listScript("raid-read")
    private val endScript = script("raid-end", Long::class.java)
    private val loadScript = script("raid-load", Long::class.java)

    fun attack(
        bossId: Long,
        memberId: Long,
        cooldown: Duration,
        now: Instant,
    ): AttackOutcome =
        guarded {
            val args = arrayOf(bossId, memberId, cooldown.toMillis(), now.toEpochMilli()).map { it.toString() }
            val result = redis.execute(attackScript, BOSS_KEYS, *args.toTypedArray()).orEmpty()
            when (result.firstOrNull()?.toString()) {
                "OK" ->
                    AttackOutcome.Accepted(
                        hp = result.int(HP_INDEX),
                        myDamage = result.int(MINE_INDEX),
                        participantCount = result.int(PARTICIPANTS_INDEX),
                        defeated = result.int(DEFEATED_INDEX) == 1,
                        maxHp = result.int(MAX_HP_INDEX),
                    )
                "COOLDOWN" -> AttackOutcome.Cooldown(Duration.ofMillis(result.int(1).toLong()))
                "ENDED" -> AttackOutcome.Ended
                else -> AttackOutcome.Reload
            }
        }

    /** 지금의 보스. Redis에 없으면 null이다. 스크립트 하나로 읽어 왕복이 한 번이다. */
    fun read(): RaidBoss? =
        guarded {
            val fields = redis.execute(readScript, BOSS_KEYS).orEmpty().map { it?.toString().orEmpty() }
            if (fields.isEmpty()) return@guarded null
            RaidBoss(
                id = fields[READ_ID].toLong(),
                emotion = EmotionType.valueOf(fields[READ_EMOTION]),
                maxHp = fields[READ_MAX_HP].toInt(),
                hp = fields[READ_HP].toInt().coerceAtLeast(0),
                status = RaidBossStatus.valueOf(fields[READ_STATUS]),
                participantCount = fields[READ_PARTICIPANTS].toInt(),
                spawnedAt = Instant.ofEpochMilli(fields[READ_SPAWNED_AT].toLong()),
                endedAt = fields[READ_ENDED_AT].toInstantOrNull(),
                epoch = fields[READ_EPOCH].toLong(),
            )
        }

    fun damageOf(
        bossId: Long,
        memberId: Long,
    ): Int =
        guarded {
            val damage = redis.opsForHash<String, String>().get(contributionsKey(bossId), memberId.toString())
            damage?.toInt() ?: 0
        }

    /** 보스의 모든 기여. 보스를 끝낼 때 통째로 적는 데 쓴다. */
    fun contributions(bossId: Long): Map<Long, Int> =
        guarded {
            redis
                .opsForHash<String, String>()
                .entries(contributionsKey(bossId))
                .map { (member, damage) -> member.toLong() to damage.toInt() }
                .toMap()
        }

    /** 아직 적지 않은 회원을 [limit]명까지 꺼내며 그 기여와 지금 HP를 함께 읽는다. 보스가 없으면 null이다. */
    fun flush(limit: Int): RaidFlush? =
        guarded {
            val result = redis.execute(flushScript, BOSS_KEYS, limit.toString()).orEmpty()
            if (result.isEmpty()) return@guarded null
            val damages =
                result
                    .drop(FLUSH_HEADER_SIZE)
                    .chunked(2)
                    .filter { it[1] != null }
                    .associate { it[0].toString().toLong() to it[1].toString().toInt() }
            RaidFlush(
                bossId = result.int(0).toLong(),
                hp = result.int(1).coerceAtLeast(0),
                status = RaidBossStatus.valueOf(result[2].toString()),
                participantCount = result.int(FLUSH_PARTICIPANTS_INDEX),
                endedAt = result[FLUSH_ENDED_AT_INDEX].toString().toInstantOrNull(),
                damages = damages,
            )
        }

    /** 적지 못한 회원을 다음 옮기기가 다시 꺼내도록 되돌린다. */
    fun markDirty(
        bossId: Long,
        memberIds: Collection<Long>,
    ) {
        if (memberIds.isEmpty()) return
        guarded { redis.opsForSet().add(dirtyKey(bossId), *memberIds.map { it.toString() }.toTypedArray()) }
    }

    /** 살아 있는 보스를 [status]로 끝낸다. 이미 끝났거나 다른 보스면 false다. */
    fun end(
        bossId: Long,
        status: RaidBossStatus,
        now: Instant,
    ): Boolean {
        val args = arrayOf(bossId.toString(), status.name, now.toEpochMilli().toString())
        return guarded { redis.execute(endScript, BOSS_KEYS, *args) == 1L }
    }

    /**
     * 기록에서 읽은 보스와 기여를 올린다. 같은 보스나 더 새 보스가 이미 있으면 아무것도 바꾸지 않는다. 기여를 먼저 채우고
     * 보스를 마지막에 올려, 올리는 동안의 공격이 반쯤 채워진 값을 보지 않게 한다.
     */
    fun load(
        boss: RaidBoss,
        damages: Map<Long, Int>,
        epoch: Long,
    ): Boolean =
        guarded {
            val current = redis.opsForHash<String, String>().get(BOSS_KEY, "id")?.toLong()
            if (current != null && current >= boss.id) return@guarded false
            redis.delete(listOf(contributionsKey(boss.id), dirtyKey(boss.id)))
            damages.entries.chunked(LOAD_CHUNK).forEach { chunk ->
                val values = chunk.associate { it.key.toString() to it.value.toString() }
                redis.opsForHash<String, String>().putAll(contributionsKey(boss.id), values)
            }
            val args =
                listOf(
                    boss.id,
                    boss.emotion.name,
                    boss.maxHp,
                    boss.hp,
                    boss.status.name,
                    boss.spawnedAt.toEpochMilli(),
                    boss.endedAt?.toEpochMilli() ?: "",
                    epoch,
                ).map { it.toString() }
            redis.execute(loadScript, BOSS_KEYS, *args.toTypedArray()) == 1L
        }

    /**
     * 다시 채우는 동안의 잠금을 잡고 [block]을 돌린다. 다른 인스턴스가 잡고 있으면 돌리지 않고 false다. 잠금은 풀지 못해도
     * [LOAD_LOCK_TTL] 뒤에 스스로 풀린다.
     */
    fun withLoadLock(block: () -> Unit): Boolean {
        val locked = guarded { redis.opsForValue().setIfAbsent(LOAD_LOCK_KEY, "1", LOAD_LOCK_TTL) == true }
        if (!locked) return false
        try {
            block()
        } finally {
            guarded { redis.delete(LOAD_LOCK_KEY) }
        }
        return true
    }

    /** 끝난 보스의 기여는 결과 화면이 보이는 동안만 둔다. */
    fun expireContributions(bossId: Long) {
        guarded {
            redis.expire(contributionsKey(bossId), ENDED_TTL)
            redis.expire(dirtyKey(bossId), ENDED_TTL)
        }
    }

    companion object {
        const val BOSS_KEY = "raid:boss"
        private val BOSS_KEYS = listOf(BOSS_KEY)
        private const val LOAD_LOCK_KEY = "raid:load-lock"
        private val LOAD_LOCK_TTL: Duration = Duration.ofSeconds(5)
        private val ENDED_TTL: Duration = Duration.ofDays(2)
        private const val LOAD_CHUNK = 1000
        private const val HP_INDEX = 1
        private const val MINE_INDEX = 2
        private const val PARTICIPANTS_INDEX = 3
        private const val DEFEATED_INDEX = 4
        private const val MAX_HP_INDEX = 5
        private const val FLUSH_PARTICIPANTS_INDEX = 3
        private const val FLUSH_ENDED_AT_INDEX = 4
        private const val FLUSH_HEADER_SIZE = 5
        private const val READ_ID = 0
        private const val READ_EMOTION = 1
        private const val READ_MAX_HP = 2
        private const val READ_HP = 3
        private const val READ_STATUS = 4
        private const val READ_SPAWNED_AT = 5
        private const val READ_ENDED_AT = 6
        private const val READ_EPOCH = 7
        private const val READ_PARTICIPANTS = 8

        fun contributionsKey(bossId: Long) = "raid:contrib:$bossId"

        fun dirtyKey(bossId: Long) = "raid:dirty:$bossId"
    }
}

private fun <T> guarded(block: () -> T): T =
    try {
        block()
    } catch (e: DataAccessException) {
        throw RaidUnavailableException(e)
    }

private fun List<*>.int(index: Int): Int = this[index].toString().toInt()

/** 스크립트가 시각을 epoch ms 문자열로 준다. 없으면 빈 문자열이다. */
private fun String.toInstantOrNull(): Instant? = takeIf { it.isNotEmpty() }?.let { Instant.ofEpochMilli(it.toLong()) }

@Suppress("UNCHECKED_CAST")
private fun listScript(name: String): DefaultRedisScript<List<Any?>> {
    val resultType = List::class.java as Class<List<Any?>>
    return script(name, resultType)
}

private fun <T : Any> script(
    name: String,
    resultType: Class<T>,
): DefaultRedisScript<T> =
    DefaultRedisScript<T>().apply {
        setLocation(ClassPathResource("redis/$name.lua"))
        setResultType(resultType)
    }
