package com.ogu.shared.error

/**
 * [retryAfterSeconds]가 있으면 `GlobalExceptionHandler`가 `Retry-After` 헤더와 `error.retryAfterSeconds`로 함께 보낸다
 * (예: `LOGIN_THROTTLED`).
 */
class BusinessException(
    val errorCode: ErrorCode,
    message: String? = null,
    val retryAfterSeconds: Int? = null,
) : RuntimeException(message ?: errorCode.message)
