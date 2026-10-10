package com.ogu.report

import com.ogu.report.application.WeekRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** 한 주의 경계(008 research R2). 2026-10-05는 월요일이다. */
class WeekRangeTest {
    @Test
    fun `US1-AC5 한국 시간 월요일 0시부터 다음 월요일 0시 전까지다`() {
        val week = WeekRange(LocalDate.parse("2026-10-05"))

        // 한국 시간 월요일 0시는 UTC로 일요일 15시다
        assertThat(week.from).isEqualTo(Instant.parse("2026-10-04T15:00:00Z"))
        assertThat(week.until).isEqualTo(Instant.parse("2026-10-11T15:00:00Z"))
        assertThat(week.end).isEqualTo(LocalDate.parse("2026-10-11"))
    }

    @Test
    fun `US1-AC5 일요일 23시 59분은 그 주이고 월요일 0시는 다음 주다`() {
        val sundayNight = Instant.parse("2026-10-11T14:59:59Z")
        val mondayMidnight = Instant.parse("2026-10-11T15:00:00Z")

        assertThat(WeekRange.containing(sundayNight).start).isEqualTo(LocalDate.parse("2026-10-05"))
        assertThat(WeekRange.containing(mondayMidnight).start).isEqualTo(LocalDate.parse("2026-10-12"))
    }

    @Test
    fun `해가 바뀌는 주도 월요일에 시작한다`() {
        val newYear = Instant.parse("2027-01-01T03:00:00Z")

        val week = WeekRange.containing(newYear)

        assertThat(week.start).isEqualTo(LocalDate.parse("2026-12-28"))
        assertThat(week.previous().start).isEqualTo(LocalDate.parse("2026-12-21"))
    }

    @Test
    fun `월요일이 아닌 날짜로는 만들 수 없다`() {
        assertThat(WeekRange.startingOn(LocalDate.parse("2026-10-06"))).isNull()
        assertThat(WeekRange.startingOn(LocalDate.parse("2026-10-05"))).isNotNull()
        val tuesday = LocalDate.parse("2026-10-06")
        assertThatThrownBy { WeekRange(tuesday) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
