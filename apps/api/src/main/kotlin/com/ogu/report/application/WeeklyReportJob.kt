package com.ogu.report.application

import com.ogu.post.PostReportApi
import com.ogu.report.WeeklyReportPublished
import com.ogu.report.domain.WeeklyReportRepository
import com.ogu.report.domain.WeeklyReportRunRepository
import com.ogu.shared.schedule.DueJob
import com.ogu.shared.schedule.DueTimers
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.convert.DurationStyle
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 회원 한 명의 리포트를 발행한다(008 research R4). 세기, 넣기, 이벤트 내기가 한 트랜잭션이다. 넣은 쪽만 이벤트를 낸다.
 * 같은 회원을 여러 번, 여러 인스턴스가 함께 불러도 리포트와 이벤트는 하나다.
 */
@Component
class WeeklyReportPublisher(
    private val collector: WeeklyStatsSource,
    private val reports: WeeklyReportRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** 새로 발행했으면 true다. 그 주에 남은 글이 없거나 이미 발행됐으면 false다. */
    @Transactional
    fun publish(
        memberId: Long,
        week: WeekRange,
    ): Boolean {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val inserted =
            collector.collect(memberId, week)?.let {
                reports.insertIfAbsent(memberId, week.start, it.stats, it.crisisWeek, now)
            }
        if (inserted != null) events.publishEvent(WeeklyReportPublished(memberId, week.start))
        return inserted != null
    }
}

/** 한 차례의 결과. [completed]면 그 주를 다 훑었고 다시 훑지 않는다. */
data class ReportTick(
    val published: Int,
    val failed: Int,
    val completed: Boolean,
)

/**
 * 지난주의 리포트를 채운다(008 research R3). 한 번 도는 배치가 아니라, 돌 때마다 "지난주 대상 회원 가운데 리포트가 없는
 * 회원"을 찾아 만든다. 그래서 일부가 실패해도 나머지는 나가고(US3-AC1), 실패한 회원과 서버가 내려가 있던 동안의 몫은
 * 다음 차례에 이어진다(US3-AC2, AC4, AC5). 재시도와 이어 하기를 따로 두지 않는다.
 *
 * 대상은 지난주뿐이다. 그 주의 리포트는 이번 주가 끝나기 전까지만 만든다.
 */
@Component
class WeeklyReportJob(
    private val postReportApi: PostReportApi,
    private val reports: WeeklyReportRepository,
    private val runs: WeeklyReportRunRepository,
    private val publisher: WeeklyReportPublisher,
    private val properties: ReportProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun tick(): ReportTick {
        val now = clock.instant()
        val thisWeek = WeekRange.containing(now)
        val target = thisWeek.previous()
        // 월요일 0시 직전에 쓴 글의 감정 분석이 끝날 시간을 준다
        if (now < publishFrom(thisWeek) || runs.isCompleted(target.start)) return ReportTick(0, 0, completed = false)
        return fill(target)
    }

    /**
     * 다음에 [tick]을 부를 시각(018 research R3). 이번 주 발행 시각 전이면 그 시각이고 DB를 읽지 않는다. 지났는데 지난주를
     * 다 훑지 않았으면 [continueAt](지났거나 없으면 지금)이고, 다 훑었으면 다음 주 월요일의 발행 시각이다.
     */
    fun nextRunAt(continueAt: Instant?): Instant {
        val now = clock.instant()
        val thisWeek = WeekRange.containing(now)
        val publishFrom = publishFrom(thisWeek)
        return when {
            now < publishFrom -> publishFrom
            runs.isCompleted(thisWeek.previous().start) -> publishFrom(WeekRange(thisWeek.start.plusWeeks(1)))
            else -> continueAt?.takeIf { it > now } ?: now
        }
    }

    private fun publishFrom(thisWeek: WeekRange): Instant =
        thisWeek.start
            .atTime(properties.publishAt)
            .atZone(WeekRange.SEOUL)
            .toInstant()

    private fun fill(target: WeekRange): ReportTick {
        var published = 0
        var failed = 0
        var afterId = 0L
        while (true) {
            val authors = postReportApi.authorIdsBetween(target.from, target.until, afterId, properties.batchSize)
            if (authors.isEmpty()) break
            afterId = authors.last()
            val missing = authors - reports.memberIdsWithReport(target.start, authors)
            for (memberId in missing) {
                if (published >= properties.maxPerTick) return ReportTick(published, failed, completed = false)
                when (publishSafely(memberId, target)) {
                    Outcome.PUBLISHED -> published++
                    Outcome.FAILED -> failed++
                    Outcome.SKIPPED -> Unit
                }
            }
        }
        if (failed == 0) {
            runs.complete(target.start, clock.instant().truncatedTo(ChronoUnit.MICROS))
            log.info("주간 리포트 만들기를 끝냈습니다: week={}, published={}", target.start, published)
        } else {
            log.warn("주간 리포트 일부가 실패했습니다. 다음 차례에 다시 합니다: week={}, failed={}", target.start, failed)
        }
        return ReportTick(published, failed, completed = failed == 0)
    }

    /**
     * 한 회원의 실패가 다른 회원을 막지 않게 받아 둔다. [Outcome.SKIPPED]는 그 사이 다른 인스턴스가 만들었거나 글을 모두
     * 지운 경우다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun publishSafely(
        memberId: Long,
        target: WeekRange,
    ): Outcome =
        try {
            if (publisher.publish(memberId, target)) Outcome.PUBLISHED else Outcome.SKIPPED
        } catch (e: RuntimeException) {
            // 수치가 예외 메시지에 섞일 수 있어 종류만 남긴다
            val cause = e.javaClass.simpleName
            log.warn("주간 리포트를 만들지 못했습니다: memberId={}, week={}, cause={}", memberId, target.start, cause)
            Outcome.FAILED
        }

    private enum class Outcome { PUBLISHED, SKIPPED, FAILED }
}

/**
 * 정해진 시각에만 [WeeklyReportJob.tick]을 부른다(018 research R3). 주기마다 DB를 읽지 않는다.
 *
 * - 이번 주 월요일 `publish-at`(05:00 한국 시간) 전이면 그 시각에 깬다. DB를 읽지 않고 시계로 정한다.
 * - 그 시각이 지났는데 지난주를 다 훑지 않았으면(`weekly_report_run`이 없으면) 바로 돈다. 기동이 늦어도 밀린 주를 채운다.
 * - 훑는 중이면(한도에 닿았거나 일부가 실패했으면) `poll-interval`(1분) 뒤에 이어 간다. 그동안은 DB가 깨어 있어야 하는 일이다.
 * - 다 훑었으면 다음 주 월요일 `publish-at`에 깬다.
 *
 * `ogu.report.scheduler-enabled=false`면 돌지 않는다. 시계를 움직이는 테스트가 [WeeklyReportJob.tick]을 직접 부를 때 쓴다.
 *
 * `ogu.report.idle-recheck`를 주면 다 훑은 뒤에도 그 간격마다 다시 확인한다. 브라우저 e2e가 글의 때를 지난주로 옮기고
 * `weekly_report_run`을 지워 다시 훑게 하는데, DB만 바뀌어서는 타이머가 알 수 없기 때문이다. 운영에서는 두지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.report", name = ["scheduler-enabled"], matchIfMissing = true)
class WeeklyReportScheduler(
    private val job: WeeklyReportJob,
    private val clock: Clock,
    @Value("\${ogu.report.poll-interval:1m}") private val continueAfter: Duration,
    timers: DueTimers,
    @Value("\${ogu.report.idle-recheck:}") idleRecheck: String = "",
) : DueJob {
    private val idleRecheck: Duration? = idleRecheck.takeIf { it.isNotBlank() }?.let(DurationStyle::detectAndParse)

    /** 훑는 중일 때 이어 갈 시각. 기동 직후에는 없다(밀린 주가 있으면 바로 돈다). */
    @Volatile
    private var continueAt: Instant? = null

    init {
        timers.register(this)
    }

    override val name = "주간 리포트"

    override fun nextDue(): Instant {
        val next = job.nextRunAt(continueAt)
        val recheckAt = idleRecheck?.let { clock.instant().plus(it) } ?: return next
        return minOf(next, recheckAt)
    }

    override fun runDue() {
        // 실패해도 1분 뒤에 다시 돈다(지금까지의 주기와 같다)
        continueAt = clock.instant().plus(continueAfter)
        job.tick()
    }
}
