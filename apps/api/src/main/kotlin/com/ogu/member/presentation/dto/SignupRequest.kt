package com.ogu.member.presentation.dto

/**
 * 계약의 `EmailPasswordRequest`. 형식 검증(이메일 형식과 길이, 비밀번호 규칙)은 정규화 뒤에 서비스가 한다.
 */
data class SignupRequest(
    val email: String,
    val password: String,
)
