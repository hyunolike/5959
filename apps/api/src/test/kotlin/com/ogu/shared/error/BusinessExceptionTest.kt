package com.ogu.shared.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BusinessExceptionTest {
    @Test
    fun `018 정해진 흐름의 거절이라 스택 추적을 만들지 않는다`() {
        val e = BusinessException(ErrorCode.RAID_COOLDOWN, retryAfterSeconds = 1)

        assertThat(e.stackTrace).isEmpty()
    }

    @Test
    fun `원인 예외는 나중에 붙일 수 있다`() {
        val cause = IllegalStateException("유일 제약 위반")

        val e = BusinessException(ErrorCode.NICKNAME_TAKEN).apply { initCause(cause) }

        assertThat(e.cause).isSameAs(cause)
        assertThat(e.message).isEqualTo(ErrorCode.NICKNAME_TAKEN.message)
    }
}
