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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T028: 내 프로필, 닉네임 확인, 온보딩 (US1-AC4~AC7, FR-007~FR-009, FR-015)
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OnboardingApiTests {
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
    fun `US1-AC4 온보딩을 마치면 프로필이 저장되고 onboarded=true인 새 access 토큰을 받는다`() {
        val member = signupMember()
        val nickname = uniqueNickname()

        val result =
            onboard(member.accessToken, " $nickname ", "DEVELOPMENT", "YEAR_3")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.member.id").value(member.id))
                .andExpect(jsonPath("$.data.member.nickname").value(nickname))
                .andExpect(jsonPath("$.data.member.jobRole").value("DEVELOPMENT"))
                .andExpect(jsonPath("$.data.member.careerYear").value("YEAR_3"))
                .andExpect(jsonPath("$.data.member.onboarded").value(true))
                .andExpect(jsonPath("$.data.accessToken").isString)
                .andExpect(jsonPath("$.data.accessTokenExpiresAt").isString)
                .andReturn()

        val newToken = data(result.response.contentAsString).get("accessToken").asString()
        val oldJwt = jwtDecoder.decode(member.accessToken)
        val newJwt = jwtDecoder.decode(newToken)
        assertThat(newJwt.getClaimAsBoolean("onboarded")).isTrue()
        assertThat(newJwt.subject).isEqualTo(member.id.toString())
        assertThat(newJwt.getClaimAsString("sid")).isEqualTo(oldJwt.getClaimAsString("sid"))

        me(newToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(member.id))
            .andExpect(jsonPath("$.data.authMethod").value("EMAIL"))
            .andExpect(jsonPath("$.data.email").value(member.email))
            .andExpect(jsonPath("$.data.nickname").value(nickname))
            .andExpect(jsonPath("$.data.jobRole").value("DEVELOPMENT"))
            .andExpect(jsonPath("$.data.careerYear").value("YEAR_3"))
            .andExpect(jsonPath("$.data.onboarded").value(true))
        val nicknameKey =
            jdbcTemplate.queryForObject("select nickname_key from member where id = ?", String::class.java, member.id)
        assertThat(nicknameKey).isEqualTo(nickname.lowercase())
        // 새 토큰은 온보딩 가드를 지난다(아직 없는 경로라 404), 옛 토큰은 여전히 가드에 막힌다
        mockMvc.perform(get(GUARDED_PATH).bearer(newToken)).andExpect(status().isNotFound)
        mockMvc.perform(get(GUARDED_PATH).bearer(member.accessToken)).andExpect(status().isForbidden)
    }

    @Test
    fun `온보딩 전에도 내 프로필을 볼 수 있다`() {
        val member = signupMember()

        me(member.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(member.id))
            .andExpect(jsonPath("$.data.email").value(member.email))
            .andExpect(jsonPath("$.data.nickname").isEmpty)
            .andExpect(jsonPath("$.data.onboarded").value(false))
    }

    @Test
    fun `토큰 없이 내 프로필, 닉네임 확인, 온보딩을 부르면 401`() {
        mockMvc.perform(get("/api/v1/members/me")).andExpect(status().isUnauthorized)
        mockMvc
            .perform(get("/api/v1/members/nickname-availability").param("nickname", "ogu"))
            .andExpect(status().isUnauthorized)
        mockMvc
            .perform(
                put("/api/v1/members/me/onboarding")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nickname":"ogu","jobRole":"HR","careerYear":"YEAR_1"}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `US1-AC5 대소문자만 다른 닉네임도 409 NICKNAME_TAKEN`() {
        val first = signupMember()
        val second = signupMember()
        val nickname = uniqueNickname()
        onboard(first.accessToken, nickname).andExpect(status().isOk)

        onboard(second.accessToken, nickname.uppercase())
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("NICKNAME_TAKEN"))
        me(second.accessToken).andExpect(jsonPath("$.data.onboarded").value(false))
    }

    @ParameterizedTest
    @ValueSource(strings = ["오구 오구", "ogu!", "ogu_1", "오구😀", "ㄱㄴㄷ", "", "   ", "가나다라마바사아자차카"])
    fun `US1-AC6 공백, 특수문자, 이모지가 든 닉네임은 400`(nickname: String) {
        val member = signupMember()

        onboard(member.accessToken, nickname)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        me(member.accessToken).andExpect(jsonPath("$.data.onboarded").value(false))
    }

    @Test
    fun `직군이나 경력이 목록에 없는 값이면 400`() {
        val member = signupMember()

        onboard(member.accessToken, uniqueNickname(), jobRole = "CEO")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        onboard(member.accessToken, uniqueNickname(), careerYear = "YEAR_10")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `닉네임 확인 API가 INVALID_FORMAT, TAKEN, 사용 가능을 구분한다`() {
        val owner = signupMember()
        val checker = signupMember()
        val taken = uniqueNickname()
        onboard(owner.accessToken, taken).andExpect(status().isOk)

        checkNickname(checker.accessToken, uniqueNickname())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.available").value(true))
            .andExpect(jsonPath("$.data.reason").isEmpty)
        checkNickname(checker.accessToken, taken.uppercase())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(false))
            .andExpect(jsonPath("$.data.reason").value("TAKEN"))
        checkNickname(checker.accessToken, "오구 오구")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(false))
            .andExpect(jsonPath("$.data.reason").value("INVALID_FORMAT"))
        checkNickname(checker.accessToken, "x".repeat(60))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(false))
            .andExpect(jsonPath("$.data.reason").value("INVALID_FORMAT"))
    }

    @Test
    fun `이미 온보딩한 회원은 409 ALREADY_ONBOARDED`() {
        val member = signupMember()
        val nickname = uniqueNickname()
        val newToken =
            data(onboard(member.accessToken, nickname).andReturn().response.contentAsString)
                .get("accessToken")
                .asString()

        onboard(newToken, uniqueNickname(), "HR", "YEAR_1")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("ALREADY_ONBOARDED"))
        me(newToken)
            .andExpect(jsonPath("$.data.nickname").value(nickname))
            .andExpect(jsonPath("$.data.jobRole").value("DEVELOPMENT"))
    }

    @Test
    fun `두 회원이 같은 닉네임으로 동시에 온보딩하면 한 명만 성공하고 다른 한 명은 409 NICKNAME_TAKEN`() {
        val members = listOf(signupMember(), signupMember())
        val nickname = uniqueNickname()
        val ready = CountDownLatch(members.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(members.size)
        try {
            val futures =
                members.mapIndexed { index, member ->
                    // 대소문자만 다르게 보내 nickname_key 유일 제약으로 판정되는지도 함께 본다
                    val requested = if (index == 0) nickname else nickname.uppercase()
                    executor.submit(
                        Callable {
                            ready.countDown()
                            start.await()
                            onboard(member.accessToken, requested).andReturn().response
                        },
                    )
                }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()
            val responses = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertThat(responses.map { it.status }).containsExactlyInAnyOrder(200, 409)
            val conflict = responses.single { it.status == 409 }
            assertThat(
                jsonMapper
                    .readTree(conflict.contentAsString)
                    .get("error")
                    .get("code")
                    .asString(),
            ).isEqualTo("NICKNAME_TAKEN")
            val saved =
                jdbcTemplate.queryForObject(
                    "select count(*) from member where nickname_key = ?",
                    Int::class.java,
                    nickname.lowercase(),
                )
            assertThat(saved).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `US1-AC7 온보딩 전 토큰으로 보호 API를 부르면 403 ONBOARDING_REQUIRED`() {
        val member = signupMember()

        mockMvc
            .perform(get(GUARDED_PATH).bearer(member.accessToken))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
    }

    private fun signupMember(): SignedUp {
        val email = "user-${UUID.randomUUID()}@example.com"
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(mapOf("email" to email, "password" to "password123"))),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val data = data(body)
        return SignedUp(
            id = data.get("member").get("id").asLong(),
            email = email,
            accessToken = data.get("tokens").get("accessToken").asString(),
        )
    }

    private fun onboard(
        accessToken: String,
        nickname: String,
        jobRole: String = "DEVELOPMENT",
        careerYear: String = "YEAR_3",
    ): ResultActions =
        mockMvc.perform(
            put("/api/v1/members/me/onboarding")
                .bearer(accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    jsonMapper.writeValueAsString(
                        mapOf("nickname" to nickname, "jobRole" to jobRole, "careerYear" to careerYear),
                    ),
                ),
        )

    private fun me(accessToken: String): ResultActions = mockMvc.perform(get("/api/v1/members/me").bearer(accessToken))

    private fun checkNickname(
        accessToken: String,
        nickname: String,
    ): ResultActions =
        mockMvc.perform(
            get("/api/v1/members/nickname-availability").param("nickname", nickname).bearer(accessToken),
        )

    private fun data(body: String): JsonNode = jsonMapper.readTree(body).get("data")

    /** 매번 다른 영문 소문자 8자. 대소문자만 다른 변형을 만들 수 있게 숫자는 쓰지 않는다. */
    private fun uniqueNickname(): String =
        UUID
            .randomUUID()
            .toString()
            .filter { it.isLetterOrDigit() }
            .map { 'a' + (it.digitToInt(16) % 26) }
            .take(8)
            .joinToString("")

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bearer(token: String) =
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")

    private data class SignedUp(
        val id: Long,
        val email: String,
        val accessToken: String,
    )

    companion object {
        /** 온보딩 허용 목록 밖의 보호 경로. 아직 컨트롤러가 없어 가드를 지나면 404가 된다. */
        private const val GUARDED_PATH = "/api/v1/test-only/guarded"
    }
}
