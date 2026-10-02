package com.ogu.member.presentation.dto

/** 계약의 refresh 요청. 토큰 원문은 로그에 남기지 않는다(toString에서 가린다). */
data class RefreshRequest(
    val refreshToken: String,
) {
    override fun toString(): String = "RefreshRequest(refreshToken=***)"
}
