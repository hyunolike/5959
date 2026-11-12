package com.ogu.shared.error

/**
 * [retryAfterSeconds]가 있으면 `GlobalExceptionHandler`가 `Retry-After` 헤더와 `error.retryAfterSeconds`로 함께 보낸다
 * (예: `LOGIN_THROTTLED`).
 *
 * 정해진 흐름의 거절이라 스택 추적을 만들지 않는다. 쿨다운 거절처럼 자주 나는 예외마다 스택을 채우면
 * CPU가 적은 운영 환경(Render 무료, CPU 0.1개)에서 요청이 밀린다(018 research R1). 어디서 던졌는지는 [errorCode]로 안다.
 */
class BusinessException(
    val errorCode: ErrorCode,
    message: String? = null,
    val retryAfterSeconds: Int? = null,
) : RuntimeException(message ?: errorCode.message) {
    // 원인(initCause)은 그대로 붙일 수 있게 생성자는 두고, 스택만 채우지 않는다.
    override fun fillInStackTrace(): Throwable = this
}
