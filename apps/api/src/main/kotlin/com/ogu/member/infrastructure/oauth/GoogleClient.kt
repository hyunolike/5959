package com.ogu.member.infrastructure.oauth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.Resource
import com.nimbusds.jose.util.ResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier
import com.ogu.member.domain.OAuthProvider
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.URL
import java.text.ParseException
import java.time.Clock
import java.time.Duration

/**
 * 구글 로그인(OpenID Connect). PKCE `code_verifier`와 `client_secret`으로 코드를 교환하고, 받은 `id_token`을 검증해
 * `sub`, `email`, `email_verified`를 읽는다(research R4).
 *
 * `id_token` 검증: 구글 JWKS 공개키로 RS256 서명, `iss`(`accounts.google.com` 또는 `https://accounts.google.com`),
 * `aud`에 우리 client id 포함(여럿이면 `azp`도 우리), `exp`(필수), `nbf`와 `iat`(있으면 미래가 아님),
 * 시계 오차 1분 허용. JWKS는 [AuthProperties.Google.jwksUri]에서 받아
 * 캐시한다(nimbus 기본값: 5분, 모르는 `kid`면 다시 받는다).
 */
class GoogleClient(
    private val settings: AuthProperties.Google,
    private val restClient: RestClient,
    private val clock: Clock,
) : OAuthProviderClient {
    override val provider = OAuthProvider.GOOGLE

    private val idTokenProcessor =
        DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector =
                JWSVerificationKeySelector(
                    JWSAlgorithm.RS256,
                    JWKSourceBuilder
                        .create<SecurityContext>(settings.jwksUri.toURL(), RestClientResourceRetriever(restClient))
                        .build(),
                )
            // 시간 검증은 주입한 Clock으로 verifyClaims에서 한다
            jwtClaimsSetVerifier = JWTClaimsSetVerifier { _, _ -> }
        }

    override fun exchange(
        code: String,
        redirectUri: String,
        codeVerifier: String?,
    ): OAuthUserInfo {
        if (codeVerifier.isNullOrBlank()) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "구글 로그인에는 codeVerifier가 필요합니다.")
        }
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("client_id", settings.clientId)
                add("client_secret", settings.clientSecret)
                add("redirect_uri", redirectUri)
                add("code", code)
                add("code_verifier", codeVerifier)
            }
        val token =
            OAuthHttp.call("google-token", onBadRequest = ErrorCode.OAUTH_CODE_INVALID) {
                restClient
                    .post()
                    .uri(settings.tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode::class.java)
            }
        val idToken = token.stringField("id_token") ?: throw OAuthHttp.unavailable("google-token", "id_token 없음")
        return verifyClaims(process(idToken))
    }

    private fun process(idToken: String): JWTClaimsSet =
        try {
            idTokenProcessor.process(idToken, null)
        } catch (e: KeySourceException) {
            throw OAuthHttp.unavailable("google-jwks", e.javaClass.simpleName, e)
        } catch (e: BadJOSEException) {
            throw OAuthHttp.codeInvalid(ID_TOKEN_STEP, e.javaClass.simpleName, e)
        } catch (e: JOSEException) {
            throw OAuthHttp.codeInvalid(ID_TOKEN_STEP, e.javaClass.simpleName, e)
        } catch (e: ParseException) {
            throw OAuthHttp.codeInvalid(ID_TOKEN_STEP, e.javaClass.simpleName, e)
        }

    private fun verifyClaims(claims: JWTClaimsSet): OAuthUserInfo {
        if (claims.issuer !in ISSUERS) rejectIdToken("iss 불일치")
        verifyAudience(claims)
        verifyTimes(claims)
        val subject = claims.subject?.takeIf { it.isNotBlank() } ?: rejectIdToken("sub 없음")
        val email = claims.getClaim("email") as? String
        val emailVerified =
            when (val value = claims.getClaim("email_verified")) {
                is Boolean -> value
                // 오래된 구글 토큰은 문자열 "true"로 준다
                is String -> value.equals("true", ignoreCase = true)
                else -> false
            }
        return OAuthUserInfo(providerUserId = subject, email = email, emailVerified = email != null && emailVerified)
    }

    /** `aud`에 우리 client id가 있어야 하고, 여럿이면 `azp`(토큰을 받은 쪽)가 우리여야 한다(OIDC Core 3.1.3.7). */
    private fun verifyAudience(claims: JWTClaimsSet) {
        val audience = claims.audience
        if (settings.clientId !in audience) rejectIdToken("aud 불일치")
        if (audience.size > 1 && claims.getClaim("azp") != settings.clientId) rejectIdToken("azp 불일치")
    }

    /**
     * `exp`는 필수이고 지나지 않아야 한다. `nbf`, `iat`는 있으면 미래가 아니어야 한다. 모두 시계 오차 1분을 허용한다.
     * nimbus 기본 검증기를 끄고 여기서 주입한 [Clock]으로 확인한다.
     */
    private fun verifyTimes(claims: JWTClaimsSet) {
        val now = clock.instant()
        val expiresAt = claims.expirationTime?.toInstant() ?: rejectIdToken("exp 없음")
        if (!now.isBefore(expiresAt.plus(CLOCK_SKEW))) rejectIdToken("exp 지남")
        val latestAllowed = now.plus(CLOCK_SKEW)
        if (claims.notBeforeTime?.toInstant()?.isAfter(latestAllowed) == true) rejectIdToken("nbf 미래")
        if (claims.issueTime?.toInstant()?.isAfter(latestAllowed) == true) rejectIdToken("iat 미래")
    }

    private fun rejectIdToken(reason: String): Nothing = throw OAuthHttp.codeInvalid(ID_TOKEN_STEP, reason)

    /** JWKS도 같은 [RestClient](같은 타임아웃)로 받는다. 실패는 nimbus가 `KeySourceException`으로 감싼다. */
    private class RestClientResourceRetriever(
        private val restClient: RestClient,
    ) : ResourceRetriever {
        override fun retrieveResource(url: URL): Resource {
            val response =
                try {
                    restClient
                        .get()
                        .uri(url.toURI())
                        .retrieve()
                        .toEntity(String::class.java)
                } catch (e: RestClientException) {
                    throw IOException("JWKS를 받지 못했습니다: ${e.javaClass.simpleName}", e)
                }
            val body = response.body ?: throw IOException("JWKS 응답이 비어 있습니다.")
            return Resource(body, response.headers.contentType?.toString())
        }
    }

    companion object {
        private const val ID_TOKEN_STEP = "google-id-token"
        private val ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
        private val CLOCK_SKEW: Duration = Duration.ofMinutes(1)
    }
}
