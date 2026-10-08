package com.ogu.emotion.domain

import java.time.Duration

/**
 * 재시도 간격(FR-004, research R2). n번째 실패 뒤에는 `min(initial × 2^(n-1), maxInterval)`만큼 기다린다.
 * 기본값은 30초에서 시작해 5분에서 멈춘다(30s, 60s, 120s, 240s, 300s, 300s…).
 */
data class Backoff(
    val initial: Duration,
    val maxInterval: Duration,
) {
    init {
        require(!initial.isNegative && !initial.isZero) { "initial은 0보다 커야 합니다." }
        require(maxInterval >= initial) { "maxInterval은 initial 이상이어야 합니다." }
    }

    /** [attempts]번 실패한 뒤 다음 시도까지 기다릴 시간. 두 배씩 늘리다가 상한에 닿으면 멈추므로 넘치지 않는다. */
    fun delayAfter(attempts: Int): Duration {
        require(attempts >= 1) { "시도 횟수는 1 이상이어야 합니다: $attempts" }
        var delay = initial
        repeat(attempts - 1) {
            delay = delay.multipliedBy(2)
            if (delay >= maxInterval) return maxInterval
        }
        return minOf(delay, maxInterval)
    }
}
