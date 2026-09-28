package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T027: `POST /api/v1/auth/signup` (US1-AC1~AC3, FR-001, FR-002)
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SignupApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtDecoder: JwtDecoder

    private val jsonMapper = JsonMapper.builder().build()

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    fun `US1-AC1 새 이메일과 규칙에 맞는 비밀번호로 가입하면 201과 onboarded=false 토큰을 받는다`() {
        val email = uniqueEmail()

        val result =
            signup("  ${email.uppercase()} ", VALID_PASSWORD)
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.newMember").value(true))
                .andExpect(jsonPath("$.data.member.authMethod").value("EMAIL"))
                .andExpect(jsonPath("$.data.member.email").value(email))
                .andExpect(jsonPath("$.data.member.nickname").isEmpty)
                .andExpect(jsonPath("$.data.member.jobRole").isEmpty)
                .andExpect(jsonPath("$.data.member.careerYear").isEmpty)
                .andExpect(jsonPath("$.data.member.onboarded").value(false))
                .andExpect(jsonPath("$.data.tokens.accessToken").isString)
                .andExpect(jsonPath("$.data.tokens.accessTokenExpiresAt").isString)
                .andExpect(jsonPath("$.data.tokens.refreshToken").isString)
                .andExpect(jsonPath("$.data.tokens.refreshTokenExpiresAt").isString)
                .andReturn()

        val data = jsonMapper.readTree(result.response.contentAsString).get("data")
        val memberId = data.get("member").get("id").asLong()
        val jwt = jwtDecoder.decode(data.get("tokens").get("accessToken").asString())
        assertThat(jwt.subject).isEqualTo(memberId.toString())
        assertThat(jwt.getClaimAsBoolean("onboarded")).isFalse()

        val sessionCount =
            jdbcTemplate.queryForObject(
                "select count(*) from auth_session where id = ?::uuid and member_id = ?",
                Int::class.java,
                jwt.getClaimAsString("sid"),
                memberId,
            )
        assertThat(sessionCount).isEqualTo(1)
        val passwordHash =
            jdbcTemplate.queryForObject("select password_hash from member where id = ?", String::class.java, memberId)
        assertThat(passwordHash).startsWith("{bcrypt}").doesNotContain(VALID_PASSWORD)
    }

    @Test
    fun `US1-AC2 이미 가입된 이메일은 409 EMAIL_ALREADY_REGISTERED`() {
        val email = uniqueEmail()
        signup(email, VALID_PASSWORD).andExpect(status().isCreated)

        signup(email, "another123")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty)
            .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"))
        assertThat(memberCount(email)).isEqualTo(1)
    }

    @Test
    fun `US1-AC2 대소문자만 다른 이메일도 409 EMAIL_ALREADY_REGISTERED`() {
        val email = uniqueEmail()
        signup(email, VALID_PASSWORD).andExpect(status().isCreated)

        signup(" ${email.uppercase()}", VALID_PASSWORD)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"))
        assertThat(memberCount(email)).isEqualTo(1)
    }

    @Test
    fun `외부 계정 회원이 쓰는 이메일로 가입하면 409 EMAIL_REGISTERED_WITH_OTHER_METHOD`() {
        val email = uniqueEmail()
        jdbcTemplate.update(
            "insert into member (auth_method, email, created_at, updated_at) values ('KAKAO', ?, now(), now())",
            email,
        )

        signup(email.uppercase(), VALID_PASSWORD)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("EMAIL_REGISTERED_WITH_OTHER_METHOD"))
        assertThat(memberCount(email)).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abcdef1", "abcdefghij1234567890x", "abcdefghij", "1234567890"])
    fun `US1-AC3 비밀번호 규칙 위반은 400`(password: String) {
        val email = uniqueEmail()

        signup(email, password)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        assertThat(memberCount(email)).isZero()
    }

    @Test
    fun `비밀번호가 8자와 20자 경계값이면 가입된다`() {
        signup(uniqueEmail(), "abcdef12").andExpect(status().isCreated)
        signup(uniqueEmail(), "abcdefghij123456789z").andExpect(status().isCreated)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "not-an-email", "a@b", "a b@example.com"])
    fun `이메일 형식이 아니면 400`(email: String) {
        signup(email, VALID_PASSWORD)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `이메일이 254자를 넘으면 400`() {
        val email = "a".repeat(243) + "@example.com"
        assertThat(email).hasSize(255)

        signup(email, VALID_PASSWORD)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `동시 가입 요청 두 개 중 하나만 성공한다`() {
        val email = uniqueEmail()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    executor.submit(
                        Callable {
                            ready.countDown()
                            start.await()
                            signup(email, VALID_PASSWORD).andReturn().response
                        },
                    )
                }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()
            val responses = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertThat(responses.map { it.status }).containsExactlyInAnyOrder(201, 409)
            val conflict = responses.single { it.status == 409 }
            assertThat(
                jsonMapper
                    .readTree(conflict.contentAsString)
                    .get("error")
                    .get("code")
                    .asString(),
            ).isEqualTo("EMAIL_ALREADY_REGISTERED")
            assertThat(memberCount(email)).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun signup(
        email: String,
        password: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(mapOf("email" to email, "password" to password))),
        )

    private fun memberCount(email: String): Int =
        requireNotNull(
            jdbcTemplate.queryForObject("select count(*) from member where email = ?", Int::class.java, email),
        )

    private fun uniqueEmail(): String = "user-${UUID.randomUUID()}@example.com"

    companion object {
        private const val VALID_PASSWORD = "password123"
    }
}
