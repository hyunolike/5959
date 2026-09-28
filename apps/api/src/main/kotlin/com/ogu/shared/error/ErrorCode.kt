package com.ogu.shared.error

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String,
) {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // 002-auth 계약(specs/002-auth/contracts/openapi.yaml)의 오류 코드
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "세션이 만료되었습니다. 다시 로그인해 주세요."),
    ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN, "온보딩을 먼저 완료해 주세요."),
    FORBIDDEN_ORIGIN(HttpStatus.FORBIDDEN, "허용되지 않은 요청 출처입니다."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    LOGIN_THROTTLED(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    OAUTH_CODE_INVALID(HttpStatus.UNAUTHORIZED, "외부 계정 인가 코드가 유효하지 않습니다."),
    EMAIL_REGISTERED_WITH_OTHER_METHOD(HttpStatus.CONFLICT, "이미 다른 방법으로 가입된 이메일입니다."),
    OAUTH_PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "외부 계정 제공자가 응답하지 않습니다."),
    NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    ALREADY_ONBOARDED(HttpStatus.CONFLICT, "이미 온보딩을 완료했습니다."),
}
