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
 * `aud`에 우리 client id 포함, `exp`(필수, 시계 오차 1분 허용). JWKS는 [AuthProperties.Google.jwksUri]에서 받아
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
            OAuthHttp.call("google-token", onClientError = ErrorCode.OAUTH_CODE_INVALID) {
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
        if (settings.clientId !in claims.audience) rejectIdToken("aud 불일치")
        val expiresAt = claims.expirationTime?.toInstant() ?: rejectIdToken("exp 없음")
        if (!clock.instant().isBefore(expiresAt.plus(CLOCK_SKEW))) rejectIdToken("exp 지남")
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
