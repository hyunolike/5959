package com.ogu.member.infrastructure.oauth

import com.ogu.member.domain.OAuthProvider
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.shared.error.ErrorCode
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

/**
 * 카카오 로그인(REST API). 카카오는 PKCE를 지원하지 않아(research R4, T001) `client_secret`만으로 코드를 교환하고,
 * 받은 access 토큰으로 `/v2/user/me`를 불러 회원번호(`id`)와 선택 동의 항목인 이메일을 읽는다.
 * 이메일은 `is_email_valid`와 `is_email_verified`가 모두 true일 때만 쓴다.
 */
class KakaoClient(
    private val settings: AuthProperties.Kakao,
    private val restClient: RestClient,
) : OAuthProviderClient {
    override val provider = OAuthProvider.KAKAO

    override fun exchange(
        code: String,
        redirectUri: String,
        codeVerifier: String?,
    ): OAuthUserInfo {
        val accessToken = requestAccessToken(code, redirectUri)
        val user =
            OAuthHttp.call("kakao-user", onBadRequest = ErrorCode.OAUTH_PROVIDER_UNAVAILABLE) {
                restClient
                    .get()
                    .uri(settings.userInfoUri)
                    .headers { it.setBearerAuth(accessToken) }
                    .retrieve()
                    .body(JsonNode::class.java)
            }
        val id = user.get("id")?.takeIf { it.isIntegralNumber } ?: throw OAuthHttp.unavailable("kakao-user", "id 없음")
        val account = user.get("kakao_account")
        val email =
            account
                ?.takeIf { it.booleanField("is_email_valid") && it.booleanField("is_email_verified") }
                ?.stringField("email")
        return OAuthUserInfo(providerUserId = id.asLong().toString(), email = email, emailVerified = email != null)
    }

    private fun requestAccessToken(
        code: String,
        redirectUri: String,
    ): String {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("client_id", settings.clientId)
                add("client_secret", settings.clientSecret)
                add("redirect_uri", redirectUri)
                add("code", code)
            }
        val token =
            OAuthHttp.call("kakao-token", onBadRequest = ErrorCode.OAUTH_CODE_INVALID) {
                restClient
                    .post()
                    .uri(settings.tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode::class.java)
            }
        return token.stringField("access_token") ?: throw OAuthHttp.unavailable("kakao-token", "access_token 없음")
    }
}
