package com.ogu.report.application

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 한 주(008 research R2). 한국 시간 월요일 0시부터 다음 월요일 0시 전까지다. 마이페이지의 주별 추이와 같은 기준이다.
 * [start]는 그 주의 월요일이다.
 */
data class WeekRange(
    val start: LocalDate,
) {
    init {
        require(start.dayOfWeek == DayOfWeek.MONDAY) { "주의 시작은 월요일이어야 합니다: $start" }
    }

    /** 그 주의 일요일. */
    val end: LocalDate get() = start.plusDays(DAYS - 1)

    /** 이 순간부터(포함). */
    val from: Instant get() = start.atStartOfDay(SEOUL).toInstant()

    /** 이 순간 전까지(제외). 다음 주의 월요일 0시다. */
    val until: Instant get() = start.plusDays(DAYS).atStartOfDay(SEOUL).toInstant()

    fun previous(): WeekRange = WeekRange(start.minusDays(DAYS))

    companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private const val DAYS = 7L

        /** [instant]가 든 주. */
        fun containing(instant: Instant): WeekRange =
            WeekRange(
                instant.atZone(SEOUL).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
            )

        /** [date]가 월요일이면 그 주, 아니면 null. 경로로 받은 날짜를 확인할 때 쓴다. */
        fun startingOn(date: LocalDate): WeekRange? = date.takeIf { it.dayOfWeek == DayOfWeek.MONDAY }?.let(::WeekRange)
    }
}
