package com.ogu.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 테스트가 직접 움직이는 시계. 앱의 [Clock] 빈을 대신한다. */
class MutableClock(
    @Volatile private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    /** [instant]로 옮긴다. 뒤로는 가지 않는다(이미 내준 토큰과 기록이 미래의 것이 되지 않게). */
    fun moveTo(instant: Instant) {
        require(!instant.isBefore(now)) { "시계를 뒤로 돌릴 수 없습니다" }
        now = instant
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}
