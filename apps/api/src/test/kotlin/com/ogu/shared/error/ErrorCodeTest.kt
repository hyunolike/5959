package com.ogu.shared.error

import com.ogu.shared.response.ApiResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus

class ErrorCodeTest {
    @ParameterizedTest(name = "{0}의 HTTP 상태는 {1}이다")
    @MethodSource("계약의 오류 코드와 상태")
    fun `계약에 정의된 오류 코드는 명시된 HTTP 상태를 가진다`(
        errorCode: ErrorCode,
        expectedStatus: HttpStatus,
    ) {
        assertThat(errorCode.status).isEqualTo(expectedStatus)
    }

    @Test
    fun `ApiResponse error는 기본적으로 retryAfterSeconds가 null이다`() {
        val response = ApiResponse.error(ErrorCode.INVALID_CREDENTIALS)

        assertThat(response.error!!.retryAfterSeconds).isNull()
    }

    @Test
    fun `ApiResponse error는 retryAfterSeconds를 전달할 수 있다`() {
        val response = ApiResponse.error(ErrorCode.LOGIN_THROTTLED, retryAfterSeconds = 900)

        assertThat(response.error!!.retryAfterSeconds).isEqualTo(900)
    }

    companion object {
        @JvmStatic
        @Suppress("unused", "FunctionNaming")
        fun `계약의 오류 코드와 상태`() =
            listOf(
                Arguments.of(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
                Arguments.of(ErrorCode.SESSION_EXPIRED, HttpStatus.UNAUTHORIZED),
                Arguments.of(ErrorCode.ONBOARDING_REQUIRED, HttpStatus.FORBIDDEN),
                Arguments.of(ErrorCode.FORBIDDEN_ORIGIN, HttpStatus.FORBIDDEN),
                Arguments.of(ErrorCode.EMAIL_ALREADY_REGISTERED, HttpStatus.CONFLICT),
                Arguments.of(ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED),
                Arguments.of(ErrorCode.LOGIN_THROTTLED, HttpStatus.TOO_MANY_REQUESTS),
                Arguments.of(ErrorCode.OAUTH_CODE_INVALID, HttpStatus.UNAUTHORIZED),
                Arguments.of(ErrorCode.EMAIL_REGISTERED_WITH_OTHER_METHOD, HttpStatus.CONFLICT),
                Arguments.of(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE, HttpStatus.BAD_GATEWAY),
                Arguments.of(ErrorCode.NICKNAME_TAKEN, HttpStatus.CONFLICT),
                Arguments.of(ErrorCode.ALREADY_ONBOARDED, HttpStatus.CONFLICT),
            )
    }
}
