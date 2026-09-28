package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.member.domain.OAuthProvider
import com.ogu.member.infrastructure.oauth.OAuthProviderClient
import com.ogu.member.infrastructure.oauth.OAuthUserInfo
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * T050: `POST /api/v1/auth/oauth/{provider}` (US3-AC1~AC3, FR-005, FR-006, research R4, R5).
 * 제공자 호출은 가짜 [OAuthProviderClient] 빈이 대신한다. 제공자 응답 해석은 KakaoClientTest, GoogleClientTest가 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OAuthLoginApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtDecoder: JwtDecoder

    @MockitoBean(name = "kakaoClient")
    lateinit var kakaoClient: OAuthProviderClient

    @MockitoBean(name = "googleClient")
    lateinit var googleClient: OAuthProviderClient

    private val jsonMapper = JsonMapper.builder().build()

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        `when`(kakaoClient.provider).thenReturn(OAuthProvider.KAKAO)
        `when`(googleClient.provider).thenReturn(OAuthProvider.GOOGLE)
    }

    @Test
    fun `US3-AC1 처음 쓰는 외부 계정은 newMember=true, onboarded=false`() {
        val kakaoId = uniqueId()
        val email = uniqueEmail()
        givenKakao("code-1", OAuthUserInfo(kakaoId, " ${email.uppercase()} ", emailVerified = true))

        val result =
            oauth("kakao", "code-1")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.newMember").value(true))
                .andExpect(jsonPath("$.data.member.authMethod").value("KAKAO"))
                .andExpect(jsonPath("$.data.member.email").value(email))
                .andExpect(jsonPath("$.data.member.onboarded").value(false))
                .andExpect(jsonPath("$.data.tokens.accessToken").isString)
                .andExpect(jsonPath("$.data.tokens.refreshToken").isString)
                .andReturn()

        val data = data(result.response.contentAsString)
        val memberId = data.get("member").get("id").asLong()
        val jwt = jwtDecoder.decode(data.get("tokens").get("accessToken").asString())
        assertThat(jwt.subject).isEqualTo(memberId.toString())
        assertThat(jwt.getClaimAsBoolean("onboarded")).isFalse()
        assertThat(
            jdbcTemplate.queryForMap(
                "select member_id, provider, email from oauth_identity where provider_user_id = ?",
                kakaoId,
            ),
        ).containsEntry("member_id", memberId).containsEntry("provider", "KAKAO").containsEntry("email", email)
    }

    @Test
    fun `US3-AC1 처음 쓰는 구글 계정도 새 회원이 된다`() {
        val sub = uniqueId()
        givenGoogle("g-code", "verifier-1", OAuthUserInfo(sub, uniqueEmail(), emailVerified = true))

        oauth("google", "g-code", GOOGLE_REDIRECT, codeVerifier = "verifier-1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.newMember").value(true))
            .andExpect(jsonPath("$.data.member.authMethod").value("GOOGLE"))
            .andExpect(jsonPath("$.data.member.onboarded").value(false))
        assertThat(identityCount(OAuthProvider.GOOGLE, sub)).isEqualTo(1)
    }

    @Test
    fun `이미 연결된 외부 계정으로 다시 로그인하면 같은 회원으로 newMember=false`() {
        val kakaoId = uniqueId()
        givenKakao("first", OAuthUserInfo(kakaoId, null, emailVerified = false))
        givenKakao("second", OAuthUserInfo(kakaoId, null, emailVerified = false))
        val firstId = memberId(oauth("kakao", "first").andExpect(status().isOk))

        oauth("kakao", "second")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.newMember").value(false))
            .andExpect(jsonPath("$.data.member.id").value(firstId))
        assertThat(identityCount(OAuthProvider.KAKAO, kakaoId)).isEqualTo(1)
    }

    @Test
    fun `US3-AC2 온보딩한 외부 계정은 onboarded=true`() {
        val sub = uniqueId()
        givenGoogle("first", "v1", OAuthUserInfo(sub, uniqueEmail(), emailVerified = true))
        givenGoogle("again", "v2", OAuthUserInfo(sub, uniqueEmail(), emailVerified = true))
        val first = data(oauth("google", "first", GOOGLE_REDIRECT, "v1").andReturn().response.contentAsString)
        onboard(first.get("tokens").get("accessToken").asString())

        val result =
            oauth("google", "again", GOOGLE_REDIRECT, "v2")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.newMember").value(false))
                .andExpect(jsonPath("$.data.member.id").value(first.get("member").get("id").asLong()))
                .andExpect(jsonPath("$.data.member.onboarded").value(true))
                .andReturn()

        val jwt = jwtDecoder.decode(data(result.response.contentAsString).get("tokens").get("accessToken").asString())
        assertThat(jwt.getClaimAsBoolean("onboarded")).isTrue()
    }

    @Test
    fun `US3-AC3 이메일 가입 회원과 같은 이메일이면 409 EMAIL_REGISTERED_WITH_OTHER_METHOD`() {
        val email = uniqueEmail()
        signup(email)
        val sub = uniqueId()
        givenGoogle("code", "v", OAuthUserInfo(sub, email.uppercase(), emailVerified = true))

        oauth("google", "code", GOOGLE_REDIRECT, "v")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("EMAIL_REGISTERED_WITH_OTHER_METHOD"))
        assertThat(memberCount(email)).isEqualTo(1)
        assertThat(identityCount(OAuthProvider.GOOGLE, sub)).isZero()
    }

    @Test
    fun `US3-AC3 카카오도 인증된 이메일이 이메일 가입 회원과 같으면 409`() {
        val email = uniqueEmail()
        signup(email)
        givenKakao("code", OAuthUserInfo(uniqueId(), email, emailVerified = true))

        oauth("kakao", "code")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("EMAIL_REGISTERED_WITH_OTHER_METHOD"))
    }

    @Test
    fun `US3-AC3 구글 email_verified=false면 충돌로 보지 않고 새 회원`() {
        val email = uniqueEmail()
        signup(email)
        val sub = uniqueId()
        givenGoogle("code", "v", OAuthUserInfo(sub, email, emailVerified = false))

        oauth("google", "code", GOOGLE_REDIRECT, "v")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.newMember").value(true))
            // 검증되지 않은 이메일은 회원 이메일로 쓰지 않는다. 그 이메일의 주인이 나중에 이메일로 가입할 수 있어야 한다.
            .andExpect(jsonPath("$.data.member.email").isEmpty)
        assertThat(memberCount(email)).isEqualTo(1)
        assertThat(identityCount(OAuthProvider.GOOGLE, sub)).isEqualTo(1)
    }

    @Test
    fun `이미 연결된 외부 계정은 이메일 가입 회원과 이메일이 같아도 로그인된다`() {
        val email = uniqueEmail()
        val kakaoId = uniqueId()
        givenKakao("first", OAuthUserInfo(kakaoId, email, emailVerified = true))
        givenKakao("second", OAuthUserInfo(kakaoId, email, emailVerified = true))
        val memberId = memberId(oauth("kakao", "first").andExpect(status().isOk))
        jdbcTemplate.update(
            "insert into member (auth_method, email, password_hash, created_at, updated_at) " +
                "values ('EMAIL', ?, '{bcrypt}x', now(), now())",
            email,
        )

        oauth("kakao", "second")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.newMember").value(false))
            .andExpect(jsonPath("$.data.member.id").value(memberId))
    }

    @Test
    fun `다른 외부 계정 회원과 이메일이 같으면 새 회원으로 가입된다`() {
        val email = uniqueEmail()
        givenKakao("kakao-code", OAuthUserInfo(uniqueId(), email, emailVerified = true))
        givenGoogle("google-code", "v", OAuthUserInfo(uniqueId(), email, emailVerified = true))
        val kakaoMember = memberId(oauth("kakao", "kakao-code").andExpect(status().isOk))

        val googleMember =
            memberId(
                oauth("google", "google-code", GOOGLE_REDIRECT, "v")
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.newMember").value(true)),
            )

        assertThat(googleMember).isNotEqualTo(kakaoMember)
        assertThat(memberCount(email)).isEqualTo(2)
    }

    @Test
    fun `이메일 없는 카카오 계정도 가입된다`() {
        val kakaoId = uniqueId()
        givenKakao("code", OAuthUserInfo(kakaoId, null, emailVerified = false))

        oauth("kakao", "code")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.newMember").value(true))
            .andExpect(jsonPath("$.data.member.authMethod").value("KAKAO"))
            .andExpect(jsonPath("$.data.member.email").isEmpty)
        assertThat(identityCount(OAuthProvider.KAKAO, kakaoId)).isEqualTo(1)
    }

    @Test
    fun `허용 목록에 없는 redirectUri는 400이고 제공자를 부르지 않는다`() {
        oauth("kakao", "code", "https://evil.example.com/api/auth/oauth/kakao/callback")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        // 앞부분만 같은 주소도 받지 않는다
        oauth("kakao", "code", "$KAKAO_REDIRECT/extra")
            .andExpect(status().isBadRequest)

        verify(kakaoClient, never()).exchange(anyString(), anyString(), anyString())
    }

    @Test
    fun `지원하지 않는 제공자는 400`() {
        oauth("naver", "code")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        oauth("KAKAO", "code").andExpect(status().isBadRequest)
        verifyNoInteractions(kakaoClient, googleClient)
    }

    @Test
    fun `code나 redirectUri가 없으면 400`() {
        mockMvc
            .perform(
                post("/api/v1/auth/oauth/kakao")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"redirectUri":"$KAKAO_REDIRECT"}"""),
            ).andExpect(status().isBadRequest)
        oauth("kakao", "  ").andExpect(status().isBadRequest)
    }

    @Test
    fun `제공자가 코드를 거절하면 401 OAUTH_CODE_INVALID`() {
        `when`(kakaoClient.exchange("used-code", KAKAO_REDIRECT, null))
            .thenThrow(BusinessException(ErrorCode.OAUTH_CODE_INVALID))

        oauth("kakao", "used-code")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("OAUTH_CODE_INVALID"))
    }

    @Test
    fun `제공자 타임아웃은 502 OAUTH_PROVIDER_UNAVAILABLE`() {
        `when`(googleClient.exchange("slow", GOOGLE_REDIRECT, "v"))
            .thenThrow(BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE))

        oauth("google", "slow", GOOGLE_REDIRECT, "v")
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_UNAVAILABLE"))
    }

    private fun givenKakao(
        code: String,
        info: OAuthUserInfo,
    ) {
        `when`(kakaoClient.exchange(code, KAKAO_REDIRECT, null)).thenReturn(info)
    }

    private fun givenGoogle(
        code: String,
        verifier: String,
        info: OAuthUserInfo,
    ) {
        `when`(googleClient.exchange(code, GOOGLE_REDIRECT, verifier)).thenReturn(info)
    }

    private fun oauth(
        provider: String,
        code: String,
        redirectUri: String = KAKAO_REDIRECT,
        codeVerifier: String? = null,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/oauth/$provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    jsonMapper.writeValueAsString(
                        mapOf("code" to code, "redirectUri" to redirectUri, "codeVerifier" to codeVerifier),
                    ),
                ),
        )

    private fun signup(email: String) {
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(mapOf("email" to email, "password" to "password123"))),
            ).andExpect(status().isCreated)
    }

    private fun onboard(accessToken: String) {
        mockMvc
            .perform(
                put("/api/v1/members/me/onboarding")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        jsonMapper.writeValueAsString(
                            mapOf(
                                "nickname" to "o${UUID.randomUUID().toString().take(8)}",
                                "jobRole" to "DEVELOPMENT",
                                "careerYear" to "YEAR_1",
                            ),
                        ),
                    ),
            ).andExpect(status().isOk)
    }

    private fun memberId(result: ResultActions): Long {
        val body = result.andReturn().response.contentAsString
        return data(body).get("member").get("id").asLong()
    }

    private fun data(body: String): JsonNode = jsonMapper.readTree(body).get("data")

    private fun memberCount(email: String): Int =
        requireNotNull(
            jdbcTemplate.queryForObject("select count(*) from member where email = ?", Int::class.java, email),
        )

    private fun identityCount(
        provider: OAuthProvider,
        providerUserId: String,
    ): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "select count(*) from oauth_identity where provider = ? and provider_user_id = ?",
                Int::class.java,
                provider.name,
                providerUserId,
            ),
        )

    private fun uniqueId(): String = UUID.randomUUID().toString()

    private fun uniqueEmail(): String = "oauth-${UUID.randomUUID()}@example.com"

    companion object {
        private const val KAKAO_REDIRECT = "http://localhost:3000/api/auth/oauth/kakao/callback"
        private const val GOOGLE_REDIRECT = "http://localhost:3000/api/auth/oauth/google/callback"
    }
}
