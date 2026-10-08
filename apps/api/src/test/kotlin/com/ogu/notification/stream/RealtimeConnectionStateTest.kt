package com.ogu.notification.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class RealtimeConnectionStateTest {
    private val events = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { events += it }
    private val results = ArrayDeque<() -> Boolean>()
    private val state = RealtimeConnectionState(ping = { results.removeFirst()() }, events = publisher)

    @Test
    fun `처음에는 UP이고 PING이 한 번 실패해서는 바뀌지 않는다`() {
        results += { false }

        state.probe()

        assertThat(state.current).isEqualTo(RealtimeConnection.UP)
        assertThat(events).isEmpty()
    }

    @Test
    fun `PING이 연속 두 번 실패하면 DOWN으로 바뀌고 변경 이벤트를 한 번 낸다`() {
        repeat(4) { results += { false } }

        repeat(4) { state.probe() }

        assertThat(state.current).isEqualTo(RealtimeConnection.DOWN)
        assertThat(events).containsExactly(RealtimeConnectionChanged(RealtimeConnection.DOWN))
    }

    @Test
    fun `실패 사이에 성공이 끼면 연속 횟수는 처음부터 센다`() {
        results += { false }
        results += { true }
        results += { false }

        repeat(3) { state.probe() }

        assertThat(state.current).isEqualTo(RealtimeConnection.UP)
        assertThat(events).isEmpty()
    }

    @Test
    fun `DOWN에서 PING이 한 번 성공하면 UP으로 돌아오고 변경 이벤트를 낸다`() {
        results += { false }
        results += { false }
        results += { true }
        results += { true }

        repeat(4) { state.probe() }

        assertThat(state.current).isEqualTo(RealtimeConnection.UP)
        assertThat(events).containsExactly(
            RealtimeConnectionChanged(RealtimeConnection.DOWN),
            RealtimeConnectionChanged(RealtimeConnection.UP),
        )
    }

    @Test
    fun `PING이 예외를 던지면 실패로 센다`() {
        results += { error("connection refused") }
        results += { error("connection refused") }

        repeat(2) { state.probe() }

        assertThat(state.current).isEqualTo(RealtimeConnection.DOWN)
    }
}
