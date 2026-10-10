package com.ogu.report

import java.time.LocalDate

/**
 * 회원 한 명의 한 주 리포트가 발행됐다. 리포트를 넣은 트랜잭션에서 한 번만 낸다. 수치와 편지를 싣지 않는다(008 FR-015).
 */
data class WeeklyReportPublished(
    val memberId: Long,
    /** 그 주의 월요일(한국 시간). */
    val weekStart: LocalDate,
)
