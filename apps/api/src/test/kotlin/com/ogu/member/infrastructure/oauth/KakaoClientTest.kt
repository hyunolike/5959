package com.ogu.member.infrastructure.oauth

import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Duration

/**
 * T051: 카카오 토큰 교환과 사용자 정보 조회. 카카오는 PKCE를 지원하지 않으므로(research R4 T001 확인 결과)
 * `code_verifier` 없이 `client_secret`으로 교환한다.
 */
class KakaoClientTest {
    private val settings =
        AuthProperties.Kakao(
            clientId = "kakao-client",
            clientSecret = "kakao-secret",
            tokenUri = URI.create("https://kauth.test/oauth/token"),
            userInfoUri = URI.create("https://kapi.test/v2/user/me"),
        )
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = KakaoClient(settings, builder.build())

    @Test
    fun `토큰 요청은 client_secret을 담은 form이고 code_verifier는 보내지 않는다`() {
        server
            .expect(requestTo(settings.tokenUri))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(
                content().formData(
                    LinkedMultiValueMap(
                        mapOf(
                            "grant_type" to listOf("authorization_code"),
                            "client_id" to listOf("kakao-client"),
                            "client_secret" to listOf("kakao-secret"),
                            "redirect_uri" to listOf(REDIRECT_URI),
                            "code" to listOf("auth-code"),
                        ),
                    ),
                ),
            ).andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON))
        server
            .expect(requestTo(settings.userInfoUri))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer kakao-access-token"))
            .andRespond(withSuccess(userJson(verified = true), MediaType.APPLICATION_JSON))

        val info = client.exchange("auth-code", REDIRECT_URI, "ignored-verifier")

        assertThat(info).isEqualTo(OAuthUserInfo("4321987654", "Kakao.User@Example.com", emailVerified = true))
        server.verify()
    }

    @Test
    fun `이메일이 유효하고 인증된 경우에만 이메일을 쓴다`() {
        expectToken()
        server
            .expect(requestTo(settings.userInfoUri))
            .andRespond(withSuccess(userJson(verified = false), MediaType.APPLICATION_JSON))

        val info = client.exchange("auth-code", REDIRECT_URI, null)

        assertThat(info).isEqualTo(OAuthUserInfo("4321987654", null, emailVerified = false))
    }

    @Test
    fun `이메일 동의를 하지 않은 계정은 이메일 없이 돌려준다`() {
        expectToken()
        server
            .expect(requestTo(settings.userInfoUri))
            .andRespond(withSuccess("""{"id":42,"kakao_account":{"has_email":false}}""", MediaType.APPLICATION_JSON))

        val info = client.exchange("auth-code", REDIRECT_URI, null)

        assertThat(info).isEqualTo(OAuthUserInfo("42", null, emailVerified = false))
    }

    @Test
    fun `토큰 요청이 4xx면 OAUTH_CODE_INVALID`() {
        server
            .expect(requestTo(settings.tokenUri))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"invalid_grant","error_code":"KOE320"}"""),
            )

        assertErrorCode(ErrorCode.OAUTH_CODE_INVALID) { client.exchange("used-code", REDIRECT_URI, null) }
    }

    @Test
    fun `토큰 요청이 5xx면 OAUTH_PROVIDER_UNAVAILABLE`() {
        server.expect(requestTo(settings.tokenUri)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE) { client.exchange("auth-code", REDIRECT_URI, null) }
    }

    @Test
    fun `토큰 요청이 타임아웃되면 OAUTH_PROVIDER_UNAVAILABLE`() {
        server.expect(requestTo(settings.tokenUri)).andRespond(withException(SocketTimeoutException("read timed out")))

        assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE) { client.exchange("auth-code", REDIRECT_URI, null) }
    }

    @Test
    fun `사용자 정보 요청이 실패하면 코드 문제가 아니므로 OAUTH_PROVIDER_UNAVAILABLE`() {
        expectToken()
        server.expect(requestTo(settings.userInfoUri)).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE) { client.exchange("auth-code", REDIRECT_URI, null) }
    }

    @Test
    fun `사용자 정보에 숫자 id가 없으면 OAUTH_PROVIDER_UNAVAILABLE`() {
        expectToken()
        server
            .expect(requestTo(settings.userInfoUri))
            .andRespond(withSuccess("""{"id":"abc"}""", MediaType.APPLICATION_JSON))

        assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE) { client.exchange("auth-code", REDIRECT_URI, null) }
    }

    @Test
    fun `토큰 응답에 access_token이 없으면 OAUTH_PROVIDER_UNAVAILABLE`() {
        server
            .expect(requestTo(settings.tokenUri))
            .andRespond(withSuccess("""{"token_type":"bearer"}""", MediaType.APPLICATION_JSON))

        assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE) { client.exchange("auth-code", REDIRECT_URI, null) }
    }

    @Test
    fun `운영 HTTP 클라이언트는 연결 3초, 읽기 5초 타임아웃을 쓴다`() {
        assertThat(OAuthHttp.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(3))
        assertThat(OAuthHttp.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(5))
    }

    @Test
    fun `응답하지 않는 제공자는 읽기 타임아웃 뒤 OAUTH_PROVIDER_UNAVAILABLE`() {
        // 연결은 받아 주지만(backlog) 아무 응답도 보내지 않는 서버
        ServerSocket(0).use { silent ->
            val slow =
                KakaoClient(
                    settings.copy(tokenUri = URI.create("http://127.0.0.1:${silent.localPort}/oauth/token")),
                    OAuthHttp.restClient(connectTimeout = Duration.ofSeconds(1), readTimeout = Duration.ofMillis(300)),
                )

            assertErrorCode(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE) { slow.exchange("auth-code", REDIRECT_URI, null) }
        }
    }

    private fun expectToken() {
        server.expect(requestTo(settings.tokenUri)).andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON))
    }

    private fun userJson(verified: Boolean): String =
        """
        {"id":4321987654,"connected_at":"2026-09-28T00:00:00Z",
         "kakao_account":{"has_email":true,"email_needs_agreement":false,"is_email_valid":true,
                          "is_email_verified":$verified,"email":"Kakao.User@Example.com"}}
        """.trimIndent()

    private fun assertErrorCode(
        expected: ErrorCode,
        call: () -> Unit,
    ) {
        assertThatThrownBy(call)
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(expected)
    }

    companion object {
        private const val REDIRECT_URI = "http://localhost:3000/api/auth/oauth/kakao/callback"
        private const val TOKEN_RESPONSE =
            """{"token_type":"bearer","access_token":"kakao-access-token","expires_in":21599,
               "refresh_token":"kakao-refresh-token","refresh_token_expires_in":5183999}"""
    }
}
