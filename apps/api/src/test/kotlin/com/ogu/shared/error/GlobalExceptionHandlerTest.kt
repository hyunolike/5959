package com.ogu.shared.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun `BusinessException을 ErrorCode에 정의된 HTTP 상태로 변환한다`() {
        val response = handler.handleBusinessException(BusinessException(ErrorCode.INVALID_REQUEST))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!.success).isFalse()
        assertThat(response.body!!.error!!.code).isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `알 수 없는 예외는 500과 INTERNAL_ERROR 코드로 변환한다`() {
        val response = handler.handleException(IllegalStateException("boom"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body!!.error!!.code).isEqualTo("INTERNAL_ERROR")
    }
}
