package com.ogu.report.application

import com.ogu.report.domain.WeeklyReport
import com.ogu.report.domain.WeeklyReportRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** 한 주의 리포트와, 있으면 바로 앞 주의 리포트. */
data class WeeklyReportDetail(
    val report: WeeklyReport,
    val previous: WeeklyReport?,
)

data class WeeklyReportPage(
    val items: List<WeeklyReport>,
    val nextCursor: String?,
)

/**
 * 내 리포트 조회(008 research R9). 늘 요청한 회원의 것만 찾는다. 다른 회원의 리포트를 가리킬 방법이 없다(SC-006).
 */
@Service
@Transactional(readOnly = true)
class WeeklyReportQueryService(
    private val reports: WeeklyReportRepository,
) {
    /**
     * [rawWeekStart] 주의 내 리포트. 날짜 형식이 틀렸거나 월요일이 아니거나 그 주의 리포트가 없으면 모두 같은 404다.
     */
    fun get(
        memberId: Long,
        rawWeekStart: String,
    ): WeeklyReportDetail {
        val week = parse(rawWeekStart)?.let(WeekRange::startingOn) ?: throw notFound()
        val report = reports.find(memberId, week.start) ?: throw notFound()
        return WeeklyReportDetail(report, reports.find(memberId, week.previous().start))
    }

    /** 최신 주부터. 커서는 앞 쪽 마지막 리포트의 주(날짜)다. */
    fun page(
        memberId: Long,
        cursor: String?,
        size: Int,
    ): WeeklyReportPage {
        if (size !in 1..MAX_SIZE) throw BusinessException(ErrorCode.INVALID_REQUEST, "size는 1 이상 $MAX_SIZE 이하여야 합니다.")
        val before =
            cursor?.let { parse(it) ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "리포트 커서가 올바르지 않습니다.") }
        val rows = reports.findPage(memberId, before, size + 1)
        val items = rows.take(size)
        return WeeklyReportPage(items, if (rows.size > size) items.last().weekStart.toString() else null)
    }

    private fun parse(raw: String): LocalDate? =
        try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun notFound() = BusinessException(ErrorCode.WEEKLY_REPORT_NOT_FOUND)

    private companion object {
        const val MAX_SIZE = 50
    }
}

/**
 * 보관 기간(1년)이 지난 리포트를 지운다(008 research R10). 하루 한 번, 묶음으로 지우고 바로 커밋한다. 여러 인스턴스가
 * 함께 돌아도 된다(지우기는 멱등이고 잠긴 행은 건너뛴다).
 */
@Component
class WeeklyReportPurgeJob(
    private val reports: WeeklyReportRepository,
    private val properties: ReportProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${ogu.report.purge-cron:0 40 4 * * *}", zone = "Asia/Seoul")
    fun purge(): Int {
        val cutoff = clock.instant().minus(properties.retention)
        var total = 0
        do {
            val deleted = reports.deletePublishedBefore(cutoff, PURGE_BATCH)
            total += deleted
        } while (deleted == PURGE_BATCH)
        if (total > 0) log.info("보관 기간이 지난 주간 리포트를 지웠습니다: {}건", total)
        return total
    }

    private companion object {
        const val PURGE_BATCH = 1000
    }
}
