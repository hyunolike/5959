package com.ogu.member.presentation.dto

/**
 * 계약의 `EmailPasswordRequest`(로그인). 비밀번호 규칙은 검사하지 않는다. 규칙에 맞지 않는 값도 어느 회원과도 맞지 않는
 * 비밀번호로 보고 같은 `401 INVALID_CREDENTIALS`로 답한다.
 */
data class LoginRequest(
    val email: String,
    val password: String,
)
