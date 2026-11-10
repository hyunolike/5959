package com.ogu.raid.application

import com.ogu.emotion.EmotionApi
import com.ogu.emotion.EmotionType
import com.ogu.post.PostApi
import com.ogu.raid.domain.RaidBoss
import com.ogu.raid.domain.RaidBossRepository
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidUnavailableException
import com.ogu.shared.schedule.DueJob
import com.ogu.shared.schedule.DueTimers
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 새 보스의 감정과 HP를 정한다(006 research R9).
 *
 * - 감정: 최근 [RaidProperties.emotionWindow](7일) 동안 쓰인, 다른 회원에게 보이는 글의 분석 결과 가운데 가장 많은 것.
 *   수가 같으면 [EmotionType]에 적힌 순서에서 앞선 것, 분석이 끝난 글이 없으면 무기력이다. 글의 내용은 쓰지 않는다.
 * - HP: 직전 보스의 참여자 수 × [RaidProperties.Hp.perParticipant]를 최소와 최대 사이로 자른다. 직전 보스가 없거나
 *   물러났으면 최소다.
 */
@Component
class RaidBossPlanner(
    private val postApi: PostApi,
    private val emotionApi: EmotionApi,
    private val properties: RaidProperties,
) {
    fun emotion(now: Instant): EmotionType {
        val postIds = postApi.visibleIdsSince(now.minus(properties.emotionWindow), MAX_POSTS)
        val counts =
            emotionApi
                .findByPostIds(postIds)
                .values
                .mapNotNull { it.emotion }
                .groupingBy { it }
                .eachCount()
        // maxByOrNull은 같은 값이면 먼저 나온 것을 고른다. entries의 순서가 곧 동률일 때의 순서다
        return EmotionType.entries.filter { it in counts }.maxByOrNull { counts.getValue(it) } ?: EmotionType.LETHARGY
    }

    fun maxHp(previous: RaidBoss?): Int {
        val hp = properties.hp
        if (previous == null || previous.status != RaidBossStatus.DEFEATED) return hp.min
        val scaled = previous.participantCount.toLong() * hp.perParticipant
        return scaled.coerceIn(hp.min.toLong(), hp.max.toLong()).toInt()
    }

    private companion object {
        const val MAX_POSTS = 10_000
    }
}

/**
 * 보스의 생애(006 US5, research R10). [RaidLifecycleScheduler]가 [nextDue]의 시각에 부르고, 두 가지를 본다.
 *
 * - 살아 있는 보스가 나타난 지 [RaidProperties.retreatAfter](7일)가 지났으면 물러나게 한다.
 * - 살아 있는 보스가 없고, 보스가 한 번도 없었거나 마지막 보스가 끝난 날(한국 시간)이 오늘보다 앞이면 새 보스를 만든다.
 *
 * 인스턴스 여럿이 함께 돌아도 살아 있는 보스는 하나다. 부분 유일 인덱스가 둘째 삽입을 막는다. Redis를 쓸 수 없으면
 * 이번 차례는 건너뛰고 false를 돌려준다.
 */
@Component
class RaidBossLifecycle(
    private val bosses: RaidBossRepository,
    private val planner: RaidBossPlanner,
    private val finisher: RaidFinisher,
    private val loader: RaidLoader,
    private val properties: RaidProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 차례가 된 일을 한다. 기록은 한 번만 읽는다(018 T019). Redis를 쓸 수 없어 건너뛰었으면 false다. */
    fun tick(): Boolean =
        try {
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val latest = bosses.findLatest()
            val currentId =
                when {
                    latest == null -> spawn(previous = null, now)
                    latest.status == RaidBossStatus.ALIVE -> latest.id.also { retreatIfOverdue(latest, now) }
                    now >= nextBossAt(latest) -> spawn(latest, now)
                    else -> latest.id
                }
            currentId?.let(loader::syncWithRecord)
            true
        } catch (e: RaidUnavailableException) {
            log.debug("Redis를 쓸 수 없어 보스의 생애 확인을 건너뜁니다", e)
            false
        }

    /**
     * 다음에 [tick]이 할 일이 생기는 시각(018 research R3). 살아 있는 보스면 물러날 시각, 끝난 보스면 다음 날 0시,
     * 보스가 한 번도 없었으면 지금이다.
     */
    fun nextDue(): Instant {
        val latest = bosses.findLatest() ?: return clock.instant()
        return if (latest.status == RaidBossStatus.ALIVE) retreatAt(latest) else nextBossAt(latest)
    }

    private fun retreatIfOverdue(
        boss: RaidBoss,
        now: Instant,
    ) {
        if (now < retreatAt(boss)) return
        loader.syncWithRecord(boss.id)
        finisher.retreat(boss.id, now)
    }

    /** 새 보스를 만들고 그 ID를 돌려준다. 다른 인스턴스가 먼저 만들었으면 그 보스의 ID다. */
    private fun spawn(
        previous: RaidBoss?,
        now: Instant,
    ): Long? {
        val bossId = bosses.insertAlive(planner.emotion(now), planner.maxHp(previous), now)
        if (bossId == null) return bosses.findLatest()?.id
        log.info("Raid boss {} spawned", bossId)
        return bossId
    }

    private fun retreatAt(boss: RaidBoss): Instant = boss.spawnedAt.plus(properties.retreatAfter)

    /** 끝난 보스의 다음 보스가 나올 시각. 같은 날에 끝났으면 다음 날 0시까지 기다린다(US5-AC3). */
    private fun nextBossAt(boss: RaidBoss): Instant = properties.nextBossAt(boss.endedAt ?: boss.spawnedAt)
}

/**
 * [RaidBossLifecycle.tick]을 정해진 시각에만 부른다(018 research R3). 주기마다 DB를 읽지 않으므로 할 일이 없으면 Neon이 잠들 수 있다.
 *
 * - 기동이 끝나면 한 번 계산한다. 보스가 한 번도 없었으면 바로 돌아 첫 보스가 나타난다(US5-AC1).
 * - 돌고 나면 [RaidBossLifecycle.nextDue]로 다음 시각을 다시 읽는다. 새 보스(`insertAlive`)는 [RaidBossLifecycle.tick]
 *   안에서만 만들어지므로 이것으로 물러날 시각이 걸린다.
 * - 처치나 물러남([RaidFinisher.finish])은 커밋 뒤에 [bossEnded]로 다시 건다. 처치되면 물러날 시각보다 이른 다음 날
 *   0시가 걸린다.
 * - Redis를 쓸 수 없어 건너뛰었으면 `lifecycle-interval`(1분) 뒤에 다시 본다. 그 사이 매초 DB를 두드리지 않는다.
 *
 * `ogu.raid.lifecycle-scheduler-enabled=false`면 타이머가 없다. 시계를 움직이는 테스트가 [RaidBossLifecycle.tick]을 직접 부를 때 쓴다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.raid", name = ["lifecycle-scheduler-enabled"], matchIfMissing = true)
class RaidLifecycleScheduler(
    private val lifecycle: RaidBossLifecycle,
    private val properties: RaidProperties,
    private val clock: Clock,
    timers: DueTimers,
) : DueJob {
    private val timer = timers.register(this)

    /** Redis를 쓸 수 없어 건너뛴 뒤 다시 볼 시각. 그 전에는 깨지 않는다. */
    @Volatile
    private var retryAt: Instant? = null

    override val name = "레이드 보스 교체"

    override fun nextDue(): Instant {
        val due = lifecycle.nextDue()
        return retryAt?.takeIf { it > due } ?: due
    }

    override fun runDue() {
        retryAt = if (lifecycle.tick()) null else clock.instant().plus(properties.lifecycleInterval)
    }

    /** 보스가 [endedAt]에 끝났다. 다음 날 0시에 돌도록 커밋 뒤에 건다. 지금 걸린 시각이 더 이르면 그대로 둔다. */
    fun bossEnded(endedAt: Instant) = timer.armAfterCommit(properties.nextBossAt(endedAt))
}
