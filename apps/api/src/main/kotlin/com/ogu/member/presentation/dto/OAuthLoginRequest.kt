package com.ogu.member.presentation.dto

/**
 * 계약의 외부 계정 로그인 요청. [redirectUri]는 인가 요청에 쓴 값 그대로이고, [codeVerifier]는 PKCE를 쓰는 구글만 보낸다.
 * 인가 코드와 verifier는 로그에 남기지 않는다(toString에서 가린다).
 */
data class OAuthLoginRequest(
    val code: String,
    val redirectUri: String,
    val codeVerifier: String? = null,
) {
    override fun toString(): String = "OAuthLoginRequest(redirectUri=$redirectUri)"
}
