package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.member.infrastructure.security.BffClientIpResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * T042: `POST /api/v1/auth/login` (US2-AC1~AC4, FR-003, FR-004)
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LoginApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var authProperties: AuthProperties

    private val jsonMapper = JsonMapper.builder().build()

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    fun `US2-AC1 올바른 이메일과 비밀번호로 로그인하면 200`() {
        val email = uniqueEmail()
        val signedUp = signupAndOnboard(email)

        val result =
            login(" ${email.uppercase()} ", VALID_PASSWORD, clientIp = uniqueIp())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.newMember").value(false))
                .andExpect(jsonPath("$.data.member.id").value(signedUp))
                .andExpect(jsonPath("$.data.member.email").value(email))
                .andExpect(jsonPath("$.data.member.onboarded").value(true))
                .andExpect(jsonPath("$.data.tokens.accessToken").isString)
                .andExpect(jsonPath("$.data.tokens.refreshToken").isString)
                .andReturn()

        val tokens = data(result.response.contentAsString).get("tokens")
        val jwt = jwtDecoder.decode(tokens.get("accessToken").asString())
        assertThat(jwt.subject).isEqualTo(signedUp.toString())
        assertThat(jwt.getClaimAsBoolean("onboarded")).isTrue()
        val activeSessions =
            jdbcTemplate.queryForObject(
                "select count(*) from auth_session where id = ?::uuid and member_id = ? and revoked_at is null",
                Int::class.java,
                jwt.getClaimAsString("sid"),
                signedUp,
            )
        assertThat(activeSessions).isEqualTo(1)
    }

    @Test
    fun `온보딩 전 회원이 로그인하면 onboarded=false 토큰을 받는다`() {
        val email = uniqueEmail()
        signup(email)

        val result =
            login(email, VALID_PASSWORD, clientIp = uniqueIp())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.member.onboarded").value(false))
                .andReturn()

        val jwt = jwtDecoder.decode(data(result.response.contentAsString).get("tokens").get("accessToken").asString())
        assertThat(jwt.getClaimAsBoolean("onboarded")).isFalse()
    }

    @Test
    fun `US2-AC2 틀린 비밀번호와 없는 이메일은 같은 401 INVALID_CREDENTIALS와 같은 메시지`() {
        val email = uniqueEmail()
        signup(email)

        val wrongPassword =
            login(email, "wrongpass123", clientIp = uniqueIp())
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty)
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn()
        val unknownEmail =
            login(uniqueEmail(), VALID_PASSWORD, clientIp = uniqueIp())
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn()

        assertThat(unknownEmail.response.contentAsString).isEqualTo(wrongPassword.response.contentAsString)
    }

    @Test
    fun `외부 계정 회원의 이메일로 로그인하면 같은 401 INVALID_CREDENTIALS`() {
        val email = uniqueEmail()
        jdbcTemplate.update(
            "insert into member (auth_method, email, created_at, updated_at) values ('KAKAO', ?, now(), now())",
            email,
        )

        login(email, VALID_PASSWORD, clientIp = uniqueIp())
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `없는 이메일로 실패해도 같은 규칙으로 센다`() {
        val email = uniqueEmail()
        val ip = uniqueIp()

        repeat(5) { login(email, "wrongpass123", clientIp = ip).andExpect(status().isUnauthorized) }

        login(email, "wrongpass123", clientIp = ip).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `US2-AC3 한 IP에서 15분 안에 5번 실패하면 올바른 비밀번호도 429이고 다른 IP는 로그인된다`() {
        val email = uniqueEmail()
        signup(email)
        val ip = uniqueIp()

        repeat(5) {
            login(email, "wrongpass123", clientIp = ip)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
        }

        login(email, VALID_PASSWORD, clientIp = ip)
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("LOGIN_THROTTLED"))
        login(email, VALID_PASSWORD, clientIp = uniqueIp()).andExpect(status().isOk)
    }

    @Test
    fun `US2-AC4 여러 IP에서 1시간 안에 20번 실패하면 모든 IP에서 429`() {
        val email = uniqueEmail()
        signup(email)

        repeat(20) {
            login(email, "wrongpass123", clientIp = uniqueIp()).andExpect(status().isUnauthorized)
        }

        repeat(2) {
            login(email, VALID_PASSWORD, clientIp = uniqueIp())
                .andExpect(status().isTooManyRequests)
                .andExpect(jsonPath("$.error.code").value("LOGIN_THROTTLED"))
        }
    }

    @Test
    fun `429 응답에 Retry-After 헤더와 retryAfterSeconds가 있고 값이 같다`() {
        val email = uniqueEmail()
        val ip = uniqueIp()
        repeat(5) { login(email, "wrongpass123", clientIp = ip) }

        val response =
            login(email, VALID_PASSWORD, clientIp = ip)
                .andExpect(status().isTooManyRequests)
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.error.retryAfterSeconds").isNumber)
                .andReturn()
                .response

        val retryAfter = requireNotNull(response.getHeader(HttpHeaders.RETRY_AFTER)).toInt()
        val body =
            jsonMapper
                .readTree(response.contentAsString)
                .get("error")
                .get("retryAfterSeconds")
                .asInt()
        assertThat(retryAfter).isEqualTo(body).isBetween(FIFTEEN_MINUTES - 5, FIFTEEN_MINUTES)
    }

    @Test
    fun `로그인에 성공하면 그 IP의 실패 수가 초기화된다`() {
        val email = uniqueEmail()
        signup(email)
        val ip = uniqueIp()
        repeat(4) { login(email, "wrongpass123", clientIp = ip).andExpect(status().isUnauthorized) }

        login(email, VALID_PASSWORD, clientIp = ip).andExpect(status().isOk)

        repeat(4) { login(email, "wrongpass123", clientIp = ip).andExpect(status().isUnauthorized) }
        login(email, VALID_PASSWORD, clientIp = ip).andExpect(status().isOk)
    }

    @Test
    fun `BFF 키가 틀리면 X-Ogu-Client-Ip를 무시한다`() {
        val email = uniqueEmail()
        signup(email)

        // 키가 틀리면 헤더의 IP가 매번 달라도 모두 원격 주소 하나로 센다.
        repeat(5) {
            login(email, "wrongpass123", clientIp = uniqueIp(), bffKey = "wrong-key")
                .andExpect(status().isUnauthorized)
        }

        login(email, VALID_PASSWORD, clientIp = uniqueIp(), bffKey = "wrong-key")
            .andExpect(status().isTooManyRequests)
        // 올바른 키로 보낸 다른 IP는 막히지 않는다.
        login(email, VALID_PASSWORD, clientIp = uniqueIp()).andExpect(status().isOk)
    }

    @Test
    fun `254자 이메일과 가장 긴 IPv6 표기로도 실패를 세고 막는다`() {
        val email = "a".repeat(230) + UUID.randomUUID().toString().take(12) + "@example.com"
        assertThat(email).hasSize(254)
        val longIp = "0000:0000:0000:0000:0000:ffff:${(1..254).random()}.${(1..254).random()}.100.200"

        repeat(5) {
            login(email, "wrongpass123", clientIp = longIp)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
        }

        login(email, "wrongpass123", clientIp = longIp)
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error.code").value("LOGIN_THROTTLED"))
    }

    @Test
    fun `BFF 키가 맞아도 X-Ogu-Client-Ip가 IP가 아니면 원격 주소로 센다`() {
        val email = uniqueEmail()
        signup(email)

        // 헤더 값이 매번 달라도 IP 리터럴이 아니면 모두 원격 주소 하나로 센다.
        repeat(5) {
            login(email, "wrongpass123", clientIp = "evil-${UUID.randomUUID()}")
                .andExpect(status().isUnauthorized)
        }

        login(email, VALID_PASSWORD, clientIp = "evil-${UUID.randomUUID()}")
            .andExpect(status().isTooManyRequests)
        login(email, VALID_PASSWORD, clientIp = uniqueIp()).andExpect(status().isOk)
    }

    @Test
    fun `이메일이나 비밀번호가 비어 있으면 400`() {
        login("", VALID_PASSWORD, clientIp = uniqueIp())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        login(uniqueEmail(), "", clientIp = uniqueIp())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `이메일이 254자를 넘으면 400`() {
        login("a".repeat(243) + "@example.com", VALID_PASSWORD, clientIp = uniqueIp())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `bcrypt 한도인 72바이트를 넘는 비밀번호는 401 INVALID_CREDENTIALS`() {
        val email = uniqueEmail()
        signup(email)

        login(email, "가".repeat(25), clientIp = uniqueIp())
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
    }

    private fun login(
        email: String,
        password: String,
        clientIp: String,
        bffKey: String = authProperties.bffKey,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/login")
                .header(BffClientIpResolver.BFF_KEY_HEADER, bffKey)
                .header(BffClientIpResolver.CLIENT_IP_HEADER, clientIp)
                .with { it.apply { remoteAddr = REMOTE_ADDR } }
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(mapOf("email" to email, "password" to password))),
        )

    private fun signup(email: String): JsonNode {
        val result =
            mockMvc
                .perform(
                    post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))),
                ).andExpect(status().isCreated)
                .andReturn()
        return data(result.response.contentAsString)
    }

    /** 가입하고 온보딩까지 마친 회원의 ID를 돌려준다. */
    private fun signupAndOnboard(email: String): Long {
        val signedUp = signup(email)
        val accessToken = signedUp.get("tokens").get("accessToken").asString()
        mockMvc
            .perform(
                put("/api/v1/members/me/onboarding")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        jsonMapper.writeValueAsString(
                            mapOf(
                                "nickname" to "u${UUID.randomUUID().toString().take(8)}",
                                "jobRole" to "DEVELOPMENT",
                                "careerYear" to "YEAR_3",
                            ),
                        ),
                    ),
            ).andExpect(status().isOk)
        return signedUp.get("member").get("id").asLong()
    }

    private fun data(body: String): JsonNode = jsonMapper.readTree(body).get("data")

    private fun uniqueEmail(): String = "user-${UUID.randomUUID()}@example.com"

    // 테스트끼리 같은 IP가 겹치지 않도록 IPv6 문서용 대역에서 무작위로 만든다.
    private fun uniqueIp(): String {
        val hex = UUID.randomUUID().toString()
        return "2001:db8::${hex.take(4)}:${hex.substring(4, 8)}"
    }

    companion object {
        private const val VALID_PASSWORD = "password123"
        private const val FIFTEEN_MINUTES = 15 * 60
        private const val REMOTE_ADDR = "10.0.0.1"
    }
}
