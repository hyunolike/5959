package com.ogu.notification.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NotificationSignalTest {
    @Test
    fun `채널 이름은 ogu_notification이다`() {
        assertThat(NotificationSignal.CHANNEL).isEqualTo("ogu:notification")
    }

    @Test
    fun `새 알림 신호는 회원ID_n_seq 형식으로 직렬화되고 그대로 해석된다`() {
        val signal = NotificationSignal.New(memberId = 42L, seq = 7L)

        assertThat(signal.encode()).isEqualTo("42:n:7")
        assertThat(NotificationSignal.parse("42:n:7")).isEqualTo(signal)
    }

    @Test
    fun `읽음 신호는 회원ID_r 형식으로 직렬화되고 그대로 해석된다`() {
        val signal = NotificationSignal.Read(memberId = 42L)

        assertThat(signal.encode()).isEqualTo("42:r")
        assertThat(NotificationSignal.parse("42:r")).isEqualTo(signal)
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = ["", "42", "42:n", "42:n:", "42:x:1", "a:n:1", "42:n:b", "42:r:1", "-1:r", "42:n:-3", " 42:r"],
    )
    fun `형식이 맞지 않는 신호는 해석하지 않는다`(raw: String) {
        assertThat(NotificationSignal.parse(raw)).isNull()
    }
}
