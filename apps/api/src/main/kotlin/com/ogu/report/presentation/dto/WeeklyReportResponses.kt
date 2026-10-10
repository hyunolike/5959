package com.ogu.report.presentation.dto

import com.ogu.emotion.EmotionType
import com.ogu.report.application.WeekRange
import com.ogu.report.application.WeeklyReportDetail
import com.ogu.report.application.WeeklyReportPage
import com.ogu.report.domain.LetterStatus
import com.ogu.report.domain.WeeklyReport
import java.time.Instant
import java.time.LocalDate

/** 계약의 `WeeklyReport`. 저장된 수치를 그대로 돌려준다. */
data class WeeklyReportResponse(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val postCount: Int,
    val emotionCounts: List<WeeklyEmotionCount>,
    val unanalyzedCount: Int,
    val topEmotion: EmotionType?,
    val defeatedCount: Int,
    val receivedLikes: Int,
    val receivedComments: Int,
    val letterStatus: LetterStatus,
    val letter: String?,
    val previous: WeeklyReportPreviousResponse?,
    val publishedAt: Instant,
) {
    companion object {
        fun from(detail: WeeklyReportDetail): WeeklyReportResponse {
            val report = detail.report
            val stats = report.stats
            return WeeklyReportResponse(
                weekStart = report.weekStart,
                weekEnd = WeekRange(report.weekStart).end,
                postCount = stats.postCount,
                emotionCounts = EmotionType.entries.map { WeeklyEmotionCount(it, stats.emotionCounts[it] ?: 0) },
                unanalyzedCount = stats.unanalyzedCount,
                topEmotion = stats.topEmotion,
                defeatedCount = stats.defeatedCount,
                receivedLikes = stats.receivedLikes,
                receivedComments = stats.receivedComments,
                letterStatus = report.letterStatus,
                letter = report.letter,
                previous =
                    detail.previous?.let { WeeklyReportPreviousResponse(it.stats.postCount, it.stats.topEmotion) },
                publishedAt = report.publishedAt,
            )
        }
    }
}

data class WeeklyEmotionCount(
    val emotion: EmotionType,
    val count: Int,
)

/** 계약의 `WeeklyReportPrevious`. */
data class WeeklyReportPreviousResponse(
    val postCount: Int,
    val topEmotion: EmotionType?,
)

/** 계약의 `WeeklyReportSummary`. */
data class WeeklyReportSummaryResponse(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val postCount: Int,
    val topEmotion: EmotionType?,
) {
    companion object {
        fun from(report: WeeklyReport) =
            WeeklyReportSummaryResponse(
                weekStart = report.weekStart,
                weekEnd = WeekRange(report.weekStart).end,
                postCount = report.stats.postCount,
                topEmotion = report.stats.topEmotion,
            )
    }
}

/** 계약의 `WeeklyReportPage`. */
data class WeeklyReportPageResponse(
    val items: List<WeeklyReportSummaryResponse>,
    val nextCursor: String?,
) {
    companion object {
        fun from(page: WeeklyReportPage): WeeklyReportPageResponse {
            val items = page.items.map(WeeklyReportSummaryResponse::from)
            return WeeklyReportPageResponse(items, page.nextCursor)
        }
    }
}
