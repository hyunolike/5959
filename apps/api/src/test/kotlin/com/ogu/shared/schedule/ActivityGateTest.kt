package com.ogu.shared.schedule

import com.ogu.support.MutableClock
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.time.Instant

class ActivityGateTest {
    private val clock = MutableClock(Instant.parse("2026-11-09T00:00:00Z"))
    private val gate = ActivityGate(clock, Duration.ofMinutes(4))
    private val filter = ActivityFilter(gate)

    @Test
    fun `기동 직후에는 활성이고 4분이 지나면 비활성이다`() {
        assertThat(gate.isActive()).isTrue()

        clock.advance(Duration.ofMinutes(4))

        assertThat(gate.isActive()).isFalse()
    }

    @Test
    fun `API 요청이 오면 다시 4분 동안 활성이다`() {
        clock.advance(Duration.ofMinutes(10))

        request("/api/v1/feed")
        clock.advance(Duration.ofMinutes(3))

        assertThat(gate.isActive()).isTrue()
    }

    @Test
    fun `US2-AC3 헬스 요청은 활동으로 세지 않는다`() {
        clock.advance(Duration.ofMinutes(10))

        request("/actuator/health/liveness")

        assertThat(gate.isActive()).isFalse()
    }

    private fun request(uri: String) {
        val request = MockHttpServletRequest("GET", uri).apply { requestURI = uri }
        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> })
    }
}
