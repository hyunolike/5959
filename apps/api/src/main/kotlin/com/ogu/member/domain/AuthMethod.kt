package com.ogu.member.domain

/**
 * 가입 방법. `NONE`은 로그인 수단이 없는 AI 회원의 값이다(009 research R2). 계약의 `AuthMethod`에는 없다.
 * AI 회원은 내 프로필을 부를 수 없어 응답에 나가지 않는다.
 */
enum class AuthMethod {
    EMAIL,
    KAKAO,
    GOOGLE,
    NONE,
}
