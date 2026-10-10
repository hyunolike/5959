package com.ogu.shared.error

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String,
) {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 콘텐츠 형식입니다."),

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

    // 003-core-loop 계약(contracts/openapi.yaml)의 오류 코드
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "글을 찾을 수 없습니다."),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
    NOT_AUTHOR(HttpStatus.FORBIDDEN, "작성자만 할 수 있습니다."),
    CANNOT_LIKE_OWN_POST(HttpStatus.FORBIDDEN, "자기 글에는 공감할 수 없습니다."),
    ALREADY_LIKED(HttpStatus.CONFLICT, "이미 공감했습니다."),
    INVALID_PARENT_COMMENT(HttpStatus.BAD_REQUEST, "답글은 원 댓글에만 달 수 있습니다."),
    POST_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "글을 너무 자주 쓰고 있습니다. 잠시 후 다시 써 주세요."),

    // 004-notification-mypage 계약(contracts/openapi.yaml)의 오류 코드
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),

    // 005 위험 감지와 안전장치
    ALREADY_REPORTED(HttpStatus.CONFLICT, "이미 신고했습니다."),
    CANNOT_REPORT_OWN_CONTENT(HttpStatus.FORBIDDEN, "내 글이나 댓글은 신고할 수 없습니다."),
    REPORT_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "신고를 너무 자주 보내고 있습니다. 잠시 후 다시 시도해 주세요."),
    REVIEW_ALREADY_REQUESTED(HttpStatus.CONFLICT, "이미 재검토를 요청했습니다."),
    RAID_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "조금 뒤에 다시 공격해 주세요."),
    RAID_BOSS_ENDED(HttpStatus.CONFLICT, "이 보스와의 레이드는 끝났습니다."),
    RAID_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "레이드가 잠시 쉬고 있습니다. 잠시 후 다시 시도해 주세요."),
    WEEKLY_REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "주간 리포트를 찾을 수 없습니다."),
    STREAM_TICKET_INVALID(HttpStatus.UNAUTHORIZED, "실시간 연결 표가 유효하지 않습니다. 다시 연결해 주세요."),
}
