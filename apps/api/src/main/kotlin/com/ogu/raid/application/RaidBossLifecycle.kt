package com.ogu.raid.application

import com.ogu.emotion.EmotionApi
import com.ogu.emotion.EmotionType
import com.ogu.post.PostApi
import com.ogu.raid.domain.RaidBoss
import com.ogu.raid.domain.RaidBossRepository
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
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
 * 보스의 생애(006 US5, research R10). [RaidLifecycleScheduler]가 주기(1분)마다 부르고, 두 가지를 본다.
 *
 * - 살아 있는 보스가 나타난 지 [RaidProperties.retreatAfter](7일)가 지났으면 물러나게 한다.
 * - 살아 있는 보스가 없고, 보스가 한 번도 없었거나 마지막 보스가 끝난 날(한국 시간)이 오늘보다 앞이면 새 보스를 만든다.
 *
 * 인스턴스 여럿이 함께 돌아도 살아 있는 보스는 하나다. 부분 유일 인덱스가 둘째 삽입을 막는다. 0시에 만들지 못했으면
 * 다음 주기에 만든다. Redis를 쓸 수 없으면 이번 주기는 건너뛴다.
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

    fun tick() {
        try {
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val latest = bosses.findLatest()
            when {
                latest == null -> spawn(previous = null, now)
                latest.status == RaidBossStatus.ALIVE -> retreatIfOverdue(latest, now)
                isBefore(latest.endedAt, now) -> spawn(latest, now)
            }
            loader.syncWithRecord()
        } catch (e: RaidUnavailableException) {
            log.debug("Redis를 쓸 수 없어 보스의 생애 확인을 건너뜁니다", e)
        }
    }

    private fun retreatIfOverdue(
        boss: RaidBoss,
        now: Instant,
    ) {
        if (now < boss.spawnedAt.plus(properties.retreatAfter)) return
        loader.syncWithRecord()
        finisher.retreat(boss.id, now)
    }

    private fun spawn(
        previous: RaidBoss?,
        now: Instant,
    ) {
        val bossId = bosses.insertAlive(planner.emotion(now), planner.maxHp(previous), now)
        if (bossId != null) log.info("Raid boss {} spawned", bossId)
    }

    /** 보스가 끝난 날이 오늘(설정한 시간대)보다 앞인가. 같은 날이면 다음 날 0시까지 기다린다(US5-AC3). */
    private fun isBefore(
        endedAt: Instant?,
        now: Instant,
    ): Boolean {
        val zone = properties.zone
        return endedAt != null && endedAt.atZone(zone).toLocalDate() < now.atZone(zone).toLocalDate()
    }
}

/**
 * [RaidBossLifecycle.tick]을 주기마다 부른다. 기동 직후에도 한 번 돌아 첫 보스가 바로 나타난다(US5-AC1).
 * `ogu.raid.lifecycle-scheduler-enabled=false`면 돌지 않는다. 시계를 움직이는 테스트가 직접 부를 때 쓴다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.raid", name = ["lifecycle-scheduler-enabled"], matchIfMissing = true)
class RaidLifecycleScheduler(
    private val lifecycle: RaidBossLifecycle,
) {
    @Scheduled(fixedDelayString = "\${ogu.raid.lifecycle-interval:1m}")
    fun run() = lifecycle.tick()
}
