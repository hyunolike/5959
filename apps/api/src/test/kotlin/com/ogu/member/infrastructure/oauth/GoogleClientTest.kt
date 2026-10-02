package com.ogu.member.infrastructure.oauth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * T051: 구글 토큰 교환(PKCE `code_verifier` 포함)과 OpenID Connect `id_token` 검증(서명, `iss`, `aud`, `exp`).
 * 서명 키는 테스트가 만든 RSA 키이고, JWKS 주소를 MockRestServiceServer가 응답한다.
 */
class GoogleClientTest {
    private val now = Instant.parse("2026-09-28T12:00:00Z")
    private val settings =
        AuthProperties.Google(
            clientId = "google-client.apps.googleusercontent.com",
            clientSecret = "google-secret",
            tokenUri = URI.create("https://oauth2.test/token"),
            jwksUri = URI.create("https://www.test/oauth2/v3/certs"),
        )
    private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("google-key-1").generate()
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = GoogleClient(settings, builder.build(), Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `토큰 요청은 code_verifier와 client_secret을 담은 form이고 id_token에서 사용자 정보를 읽는다`() {
        server
            .expect(requestTo(settings.tokenUri))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(
                content().formData(
                    LinkedMultiValueMap(
                        mapOf(
                            "grant_type" to listOf("authorization_code"),
                            "client_id" to listOf(settings.clientId),
                            "client_secret" to listOf("google-secret"),
                            "redirect_uri" to listOf(REDIRECT_URI),
                            "code" to listOf("auth-code"),
                            "code_verifier" to listOf("pkce-verifier"),
                        ),
                    ),
                ),
            ).andRespond(withSuccess(tokenResponse(idToken()), MediaType.APPLICATION_JSON))
        expectJwks()

        val info = client.exchange("auth-code", REDIRECT_URI, "pkce-verifier")

        assertThat(info).isEqualTo(OAuthUserInfo("109876543210", "Google.User@Example.com", emailVerified = true))
        server.verify()
    }

    @Test
    fun `email_verified가 false면 그대로 알린다`() {
        expectToken(idToken { it.claim("email_verified", false) })
        expectJwks()

        val info = client.exchange("auth-code", REDIRECT_URI, "pkce-verifier")

        assertThat(info.emailVerified).isFalse()
    }

    @Test
    fun `iss가 accounts google com이어도 받는다`() {
        expectToken(idToken { it.issuer("accounts.google.com") })
        expectJwks()

        assertThat(client.exchange("auth-code", REDIRECT_URI, "pkce-verifier").providerUserId).isEqualTo("109876543210")
    }

    @Test
    fun `iss가 구글이 아니면 OAUTH_CODE_INVALID`() {
        expectToken(idToken { it.issuer("https://evil.example.com") })
        expectJwks()

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID)
    }

    @Test
    fun `aud가 우리 client id가 아니면 OAUTH_CODE_INVALID`() {
        expectToken(idToken { it.audience("someone-else.apps.googleusercontent.com") })
        expectJwks()

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID)
    }

    @Test
    fun `exp가 지났으면 OAUTH_CODE_INVALID`() {
        expectToken(idToken { it.expirationTime(Date.from(now.minus(Duration.ofMinutes(5)))) })
        expectJwks()

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID)
    }

    @Test
    fun `exp가 없으면 OAUTH_CODE_INVALID`() {
        expectToken(idToken { it.expirationTime(null) })
        expectJwks()

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID)
    }

    @Test
    fun `nbf가 시계 오차 1분보다 더 미래면 OAUTH_CODE_INVALID`() {
        expectToken(idToken { it.notBeforeTime(Date.from(now.plus(Duration.ofMinutes(5)))) })
        expectJwks()

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID)
    }

    @Test
    fun `nbf와 iat가 시계 오차 1분 안의 미래면 받는다`() {
        val soon = Date.from(now.plusSeconds(30))
        expectToken(idToken { it.notBeforeTime(soon).issueTime(soon) })
        expectJwks()

        assertThat(client.exchange("auth-code", REDIRECT_URI, "pkce-verifier").providerUserId).isEqualTo("109876543210")
    }

    @Test
    fun `iat가 시계 오차 1분보다 더 미래면 OAUTH_CODE_INVALID`() {
        expectToken(idToken { it.issueTime(Date.from(now.plus(Duration.ofMinutes(5)))) })
        expectJwks()

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID)
    }

    @Test
    fun `aud가 여럿이면 azp가 우리 client id일 때만 받는다`() {
        expectToken(idToken { it.audience(listOf(settings.clientId, "other")).claim("azp", settings.clientId) })
        expectJwks()

        assertThat(client.exchange("auth-code", REDIRECT_URI, "pkce-verifier").providerUserId).isEqualTo("109876543210")
    }

    @Test
    fun `aud가 여럿인데 azp가 없으면 OAUTH_CODE_INVALID`() {
        expectToken(idToken { it.audience(listOf(settings.clientId, "other")) })
        expectJwks()

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID)
    }

    @Test
    fun `aud가 여럿인데 azp가 다른 client면 OAUTH_CODE_INVALID`() {
        expectToken(idToken { it.audience(listOf(settings.clientId, "other")).claim("azp", "other") })
        expectJwks()

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID)
    }

    @Test
    fun `JWKS에 없는 키로 서명한 id_token은 OAUTH_CODE_INVALID`() {
        val otherKey = RSAKeyGenerator(2048).keyID("google-key-1").generate()
        expectToken(idToken(key = otherKey))
        expectJwks()

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID)
    }

    @Test
    fun `codeVerifier 없이 부르면 400 INVALID_REQUEST이고 구글을 부르지 않는다`() {
        assertThatThrownBy { client.exchange("auth-code", REDIRECT_URI, null) }
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_REQUEST)
        server.verify()
    }

    @Test
    fun `토큰 요청이 4xx면 OAUTH_CODE_INVALID`() {
        server
            .expect(requestTo(settings.tokenUri))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"invalid_grant","error_description":"Bad Request"}"""),
            )

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID)
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 429])
    fun `토큰 요청의 401, 403, 429는 우리 설정이나 한도 문제이므로 OAUTH_PROVIDER_UNAVAILABLE`(status: Int) {
        server
            .expect(requestTo(settings.tokenUri))
            .andRespond(
                withStatus(HttpStatus.valueOf(status))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"invalid_client"}"""),
            )

        assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
    }

    @Test
    fun `토큰 요청이 5xx면 OAUTH_PROVIDER_UNAVAILABLE`() {
        server.expect(requestTo(settings.tokenUri)).andRespond(withStatus(HttpStatus.BAD_GATEWAY))

        assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
    }

    @Test
    fun `토큰 요청이 타임아웃되면 OAUTH_PROVIDER_UNAVAILABLE`() {
        server.expect(requestTo(settings.tokenUri)).andRespond(withException(SocketTimeoutException("timed out")))

        assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
    }

    @Test
    fun `JWKS를 받지 못하면 OAUTH_PROVIDER_UNAVAILABLE`() {
        expectToken(idToken())
        server.expect(requestTo(settings.jwksUri)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
    }

    @Test
    fun `토큰 응답에 id_token이 없으면 OAUTH_PROVIDER_UNAVAILABLE`() {
        server
            .expect(requestTo(settings.tokenUri))
            .andRespond(withSuccess("""{"access_token":"at","token_type":"Bearer"}""", MediaType.APPLICATION_JSON))

        assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
    }

    private fun expectToken(idToken: String) {
        server
            .expect(requestTo(settings.tokenUri))
            .andRespond(withSuccess(tokenResponse(idToken), MediaType.APPLICATION_JSON))
    }

    private fun expectJwks() {
        server
            .expect(requestTo(settings.jwksUri))
            .andRespond(withSuccess(JWKSet(signingKey.toPublicJWK()).toString(), MediaType.APPLICATION_JSON))
    }

    private fun idToken(
        key: RSAKey = signingKey,
        customize: (JWTClaimsSet.Builder) -> Unit = {},
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer("https://accounts.google.com")
                .audience(settings.clientId)
                .subject("109876543210")
                .issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .claim("email", "Google.User@Example.com")
                .claim("email_verified", true)
                .also(customize)
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    private fun tokenResponse(idToken: String): String =
        """{"access_token":"google-access-token","expires_in":3599,"token_type":"Bearer",
           "scope":"openid email","id_token":"$idToken"}"""

    private fun assertErrorCode(expected: ErrorCode) {
        assertThatThrownBy { client.exchange("auth-code", REDIRECT_URI, "pkce-verifier") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(expected)
    }

    companion object {
        private const val REDIRECT_URI = "http://localhost:3000/api/auth/oauth/google/callback"
    }
}
