package com.ogu.shared.error

import com.ogu.shared.response.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ApiResponse<Unit>> {
        log.warn("BusinessException: code={}, message={}", e.errorCode.name, e.message)
        val retryAfterSeconds = e.retryAfterSeconds
        val response = ResponseEntity.status(e.errorCode.status)
        if (retryAfterSeconds != null) {
            response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        }
        return response.body(ApiResponse.error(e.errorCode, e.message, retryAfterSeconds))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Unit>> {
        val message =
            e.bindingResult.fieldErrors
                .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(ErrorCode.INVALID_REQUEST, message))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ApiResponse<Unit>> {
        log.warn("HttpMessageNotReadableException: {}", e.message)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(ErrorCode.INVALID_REQUEST))
    }

    /** 경로 변수나 쿼리 파라미터의 형식이 틀렸다(예: `GET /api/v1/posts/abc`). 클라이언트 잘못이므로 400이다. */
    @ExceptionHandler(TypeMismatchException::class)
    fun handleTypeMismatch(e: TypeMismatchException): ResponseEntity<ApiResponse<Unit>> {
        log.warn("TypeMismatchException: property={}, requiredType={}", e.propertyName, e.requiredType?.simpleName)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(ErrorCode.INVALID_REQUEST))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ApiResponse<Unit>> {
        // Spring MVC가 상태 코드를 정해 둔 예외(404 NoResourceFoundException, 405, 415 등)는 그 상태를 그대로 쓴다.
        // ErrorResponse는 예외 클래스가 아닌 인터페이스라 @ExceptionHandler로 직접 지정할 수 없다.
        if (e is ErrorResponse) {
            return handleErrorResponse(e)
        }
        log.error("Unhandled exception", e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR))
    }

    private fun handleErrorResponse(e: ErrorResponse): ResponseEntity<ApiResponse<Unit>> {
        val status = e.statusCode
        val errorCode =
            when (status.value()) {
                HttpStatus.NOT_FOUND.value() -> ErrorCode.NOT_FOUND
                HttpStatus.METHOD_NOT_ALLOWED.value() -> ErrorCode.METHOD_NOT_ALLOWED
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value() -> ErrorCode.UNSUPPORTED_MEDIA_TYPE
                else -> if (status.is5xxServerError) ErrorCode.INTERNAL_ERROR else ErrorCode.INVALID_REQUEST
            }
        if (status.is5xxServerError) {
            log.error("Spring MVC error response: status={}", status.value(), e as Throwable)
        } else if (status.value() == HttpStatus.NOT_FOUND.value()) {
            log.debug("Not found: {}", (e as Throwable).message)
        } else {
            log.warn("Spring MVC error response: status={}, message={}", status.value(), (e as Throwable).message)
        }
        return ResponseEntity
            .status(status)
            .headers(e.headers)
            .body(ApiResponse.error(errorCode))
    }
}
