package com.ogu.notification.stream

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * 같은 경고가 장애 동안 쏟아지지 않게 [interval]에 한 번만 허용한다. 막힌 것은 호출한 쪽이 DEBUG로 남긴다.
 * [nanoTime]은 테스트가 시계를 움직이려고 바꾼다.
 */
class LogThrottle(
    interval: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val intervalNanos = interval.toNanos()
    private val last = AtomicLong(Long.MIN_VALUE)

    fun tryAcquire(): Boolean {
        val now = nanoTime()
        val previous = last.get()
        val due = previous == Long.MIN_VALUE || now - previous >= intervalNanos
        return due && last.compareAndSet(previous, now)
    }
}
