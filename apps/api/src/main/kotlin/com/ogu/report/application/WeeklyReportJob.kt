package com.ogu.report.application

import com.ogu.post.PostReportApi
import com.ogu.report.WeeklyReportPublished
import com.ogu.report.domain.WeeklyReportRepository
import com.ogu.report.domain.WeeklyReportRunRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
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
 * `poll-interval`(1분)마다 [WeeklyReportJob.tick]을 부른다. `ogu.report.scheduler-enabled=false`면 돌지 않는다. 시계를
 * 움직이는 테스트가 직접 부를 때 쓴다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.report", name = ["scheduler-enabled"], matchIfMissing = true)
class WeeklyReportScheduler(
    private val job: WeeklyReportJob,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("TooGenericExceptionCaught") // 한 번 실패해도 다음 주기에 다시 돈다
    @Scheduled(fixedDelayString = "\${ogu.report.poll-interval:1m}")
    fun run() {
        try {
            job.tick()
        } catch (e: RuntimeException) {
            log.error("주간 리포트 주기가 실패했습니다", e)
        }
    }
}
