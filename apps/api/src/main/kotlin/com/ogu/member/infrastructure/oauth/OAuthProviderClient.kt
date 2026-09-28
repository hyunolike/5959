package com.ogu.member.infrastructure.oauth

import com.ogu.member.domain.OAuthProvider

/**
 * 제공자와 인가 코드를 교환해 사용자 정보를 받는다(research R4).
 *
 * 제공자가 코드를 거절하면(토큰 요청의 400) `OAUTH_CODE_INVALID`, 다른 4xx(401, 403, 429), 연결 실패, 타임아웃,
 * 5xx, 해석할 수 없는 응답이면 `OAUTH_PROVIDER_UNAVAILABLE`을 `BusinessException`으로 던진다. 코드, 토큰, `id_token`은 로그에 남기지 않는다.
 */
interface OAuthProviderClient {
    val provider: OAuthProvider

    /** [codeVerifier]는 PKCE를 쓰는 제공자(구글)만 쓴다. 카카오는 PKCE를 지원하지 않아 무시한다(research R4 T001). */
    fun exchange(
        code: String,
        redirectUri: String,
        codeVerifier: String?,
    ): OAuthUserInfo
}

/**
 * 제공자가 알려 준 사용자. [email]은 제공자가 준 그대로이고, [emailVerified]가 true일 때만 이메일 충돌 판단과
 * 회원 이메일에 쓴다(research R5).
 */
data class OAuthUserInfo(
    val providerUserId: String,
    val email: String?,
    val emailVerified: Boolean,
)
