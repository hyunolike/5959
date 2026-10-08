package com.ogu.notification.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class LogThrottleTest {
    private var now = 0L
    private val throttle = LogThrottle(Duration.ofMinutes(1)) { now }

    @Test
    fun `처음은 허용하고 1분 안의 다음 것은 막고 1분이 지나면 다시 허용한다`() {
        assertThat(throttle.tryAcquire()).isTrue()

        now += Duration.ofSeconds(59).toNanos()
        assertThat(throttle.tryAcquire()).isFalse()

        now += Duration.ofSeconds(1).toNanos()
        assertThat(throttle.tryAcquire()).isTrue()
        assertThat(throttle.tryAcquire()).isFalse()
    }
}
