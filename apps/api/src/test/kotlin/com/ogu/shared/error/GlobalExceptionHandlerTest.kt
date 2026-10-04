package com.ogu.shared.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.servlet.resource.NoResourceFoundException

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
    fun `재시도까지 남은 초가 있는 BusinessException은 Retry-After 헤더와 retryAfterSeconds를 함께 준다`() {
        val response =
            handler.handleBusinessException(BusinessException(ErrorCode.LOGIN_THROTTLED, retryAfterSeconds = 120))

        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(response.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("120")
        assertThat(response.body!!.error!!.code).isEqualTo("LOGIN_THROTTLED")
        assertThat(response.body!!.error!!.retryAfterSeconds).isEqualTo(120)
    }

    @Test
    fun `재시도 시간이 없는 BusinessException에는 Retry-After 헤더가 없다`() {
        val response = handler.handleBusinessException(BusinessException(ErrorCode.INVALID_CREDENTIALS))

        assertThat(response.headers.containsHeader(HttpHeaders.RETRY_AFTER)).isFalse()
        assertThat(response.body!!.error!!.retryAfterSeconds).isNull()
    }

    @Test
    fun `처리기가 없는 경로는 404 NOT_FOUND다`() {
        val response = handler.handleException(NoResourceFoundException(HttpMethod.GET, "/v3/api-docs", "v3/api-docs"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!.success).isFalse()
        assertThat(response.body!!.error!!.code).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `지원하지 않는 메서드는 405 METHOD_NOT_ALLOWED이고 Allow 헤더를 유지한다`() {
        val response = handler.handleException(HttpRequestMethodNotSupportedException("DELETE", listOf("GET")))

        assertThat(response.statusCode).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
        assertThat(response.body!!.error!!.code).isEqualTo("METHOD_NOT_ALLOWED")
        assertThat(response.headers.allow).containsExactly(HttpMethod.GET)
    }

    @Test
    fun `지원하지 않는 미디어 타입은 415 UNSUPPORTED_MEDIA_TYPE이다`() {
        val response = handler.handleException(HttpMediaTypeNotSupportedException("text/plain"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        assertThat(response.body!!.error!!.code).isEqualTo("UNSUPPORTED_MEDIA_TYPE")
    }

    @Test
    fun `그 밖의 Spring MVC 4xx 예외는 자기 상태 코드와 INVALID_REQUEST로 응답한다`() {
        val response = handler.handleException(MissingServletRequestParameterException("nickname", "String"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!.error!!.code).isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `알 수 없는 예외는 500과 INTERNAL_ERROR 코드로 변환한다`() {
        val response = handler.handleException(IllegalStateException("boom"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body!!.error!!.code).isEqualTo("INTERNAL_ERROR")
    }
}
