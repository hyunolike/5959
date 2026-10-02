package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.OAuthIdentityRepository
import com.ogu.member.domain.OAuthProvider
import com.ogu.member.infrastructure.oauth.OAuthProviderClient
import com.ogu.member.infrastructure.oauth.OAuthUserInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import org.mockito.invocation.InvocationOnMock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
 * 가입 경합과 DB 유일 제약 처리.
 *
 * - 이메일 가입과 첫 외부 로그인은 정규화한 이메일의 advisory lock(`pg_advisory_xact_lock`)으로 직렬화한다.
 *   `member_email_key`는 이메일 가입끼리만 막으므로, 잠금이 없으면 두 요청이 모두 사전 확인을 지나 같은 이메일의
 *   이메일 회원과 외부 계정 회원이 함께 생긴다.
 * - 사전 확인을 지나친 요청이 유일 제약에 걸리면, 기대한 제약일 때만 409(또는 재시도)로 바꾼다.
 *   사전 확인을 스파이로 비워 제약 경로를 결정적으로 태운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RegistrationRaceApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @MockitoSpyBean
    lateinit var memberRepository: MemberRepository

    @MockitoSpyBean
    lateinit var identityRepository: OAuthIdentityRepository

    @MockitoBean(name = "kakaoClient")
    lateinit var kakaoClient: OAuthProviderClient

    private val jsonMapper = JsonMapper.builder().build()

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        `when`(kakaoClient.provider).thenReturn(OAuthProvider.KAKAO)
    }

    @Test
    fun `같은 이메일로 이메일 가입과 첫 외부 로그인이 동시에 오면 회원은 한 명만 생긴다`() {
        val email = uniqueEmail()
        `when`(kakaoClient.exchange("race", KAKAO_REDIRECT, null))
            .thenReturn(OAuthUserInfo(UUID.randomUUID().toString(), email, emailVerified = true))
        // 두 요청이 모두 사전 확인(findAllByEmail)을 지난 뒤에야 저장하도록 붙잡는다. 잠금이 있으면 뒤 요청은 잠금에서
        // 기다리므로 앞 요청은 2초 뒤 혼자 진행하고, 뒤 요청은 커밋된 회원을 보고 409가 된다.
        val bothChecked = CountDownLatch(2)
        doAnswer { invocation ->
            val result = callReal(memberRepository, invocation)
            bothChecked.countDown()
            bothChecked.await(2, TimeUnit.SECONDS)
            result
        }.`when`(memberRepository).findAllByEmail(email)

        val responses =
            runConcurrently(
                { signup(email) },
                { oauthKakao("race") },
            )

        assertThat(responses.map { it.status }.sorted()).isIn(listOf(200, 409), listOf(201, 409))
        val conflict = responses.single { it.status == 409 }
        // 어느 쪽이 먼저든 뒤 요청은 다른 방법으로 가입된 이메일을 만난다
        assertThat(errorCode(conflict)).isEqualTo("EMAIL_REGISTERED_WITH_OTHER_METHOD")
        assertThat(memberCount(email)).isEqualTo(1)
    }

    @Test
    fun `사전 확인을 지나친 같은 이메일 가입은 member_email_key 제약으로 409 EMAIL_ALREADY_REGISTERED`() {
        val email = uniqueEmail()
        signupExpecting(email, 201)
        doReturn(emptyList<Member>()).`when`(memberRepository).findAllByEmail(email)

        mockMvc
            .perform(signupRequest(email))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"))
        assertThat(memberCount(email)).isEqualTo(1)
    }

    @Test
    fun `사전 확인을 지나친 같은 닉네임 온보딩은 nickname_key 제약으로 409 NICKNAME_TAKEN`() {
        val nickname = "n${UUID.randomUUID().toString().take(8)}"
        onboard(accessToken(signupExpecting(uniqueEmail(), 201)), nickname).andExpect(status().isOk)
        val second = accessToken(signupExpecting(uniqueEmail(), 201))
        doReturn(false).`when`(memberRepository).existsByNicknameKey(nickname.lowercase())

        onboard(second, nickname.uppercase())
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("NICKNAME_TAKEN"))
    }

    @Test
    fun `사전 확인을 지나친 외부 계정 연결이 provider_user_key 제약에 걸리면 다시 조회해 기존 회원으로 로그인한다`() {
        val kakaoId = UUID.randomUUID().toString()
        val info = OAuthUserInfo(kakaoId, null, emailVerified = false)
        `when`(kakaoClient.exchange("first", KAKAO_REDIRECT, null)).thenReturn(info)
        `when`(kakaoClient.exchange("second", KAKAO_REDIRECT, null)).thenReturn(info)
        val firstBody = oauthKakao("first")
        assertThat(firstBody.status).isEqualTo(200)
        val firstMemberId =
            jsonMapper
                .readTree(firstBody.contentAsString)
                .get("data")
                .get("member")
                .get("id")
                .asLong()
        // 첫 조회만 "연결 없음"으로 속인다. 저장이 제약에 걸린 뒤 새 트랜잭션의 두 번째 조회는 실제 값을 본다.
        doReturn(null)
            .doAnswer { callReal(identityRepository, it) }
            .`when`(identityRepository)
            .findByProviderAndProviderUserId(OAuthProvider.KAKAO, kakaoId)

        val second = oauthKakao("second")

        assertThat(second.status).isEqualTo(200)
        val data = jsonMapper.readTree(second.contentAsString).get("data")
        assertThat(data.get("newMember").asBoolean()).isFalse()
        assertThat(data.get("member").get("id").asLong()).isEqualTo(firstMemberId)
        val kakaoMembers =
            jdbcTemplate.queryForObject(
                "select count(*) from oauth_identity where provider = 'KAKAO' and provider_user_id = ?",
                Int::class.java,
                kakaoId,
            )
        assertThat(kakaoMembers).isEqualTo(1)
    }

    /**
     * 저장소는 JDK 프록시라 스파이가 원본을 상속하지 못하고 위임한다(`callRealMethod`는 쓸 수 없다).
     * 스파이의 기본 응답이 그 위임이므로 그대로 불러 원래 동작을 실행한다.
     */
    private fun callReal(
        spy: Any,
        invocation: InvocationOnMock,
    ): Any? = mockingDetails(spy).mockCreationSettings.defaultAnswer.answer(invocation)

    private fun runConcurrently(vararg actions: () -> MockHttpServletResponse): List<MockHttpServletResponse> {
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(actions.size)
        try {
            val futures =
                actions.map { action ->
                    executor.submit(
                        Callable {
                            ready.countDown()
                            start.await()
                            action()
                        },
                    )
                }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun signup(email: String): MockHttpServletResponse {
        val result = mockMvc.perform(signupRequest(email)).andReturn()
        return result.response
    }

    private fun signupExpecting(
        email: String,
        expectedStatus: Int,
    ): MockHttpServletResponse = signup(email).also { assertThat(it.status).isEqualTo(expectedStatus) }

    private fun signupRequest(email: String) =
        post("/api/v1/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonMapper.writeValueAsString(mapOf("email" to email, "password" to "password123")))

    private fun oauthKakao(code: String): MockHttpServletResponse =
        mockMvc
            .perform(
                post("/api/v1/auth/oauth/kakao")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(mapOf("code" to code, "redirectUri" to KAKAO_REDIRECT))),
            ).andReturn()
            .response

    private fun onboard(
        accessToken: String,
        nickname: String,
    ) = mockMvc.perform(
        put("/api/v1/members/me/onboarding")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                jsonMapper.writeValueAsString(
                    mapOf("nickname" to nickname, "jobRole" to "DEVELOPMENT", "careerYear" to "YEAR_1"),
                ),
            ),
    )

    private fun accessToken(response: MockHttpServletResponse): String =
        jsonMapper
            .readTree(response.contentAsString)
            .get("data")
            .get("tokens")
            .get("accessToken")
            .asString()

    private fun errorCode(response: MockHttpServletResponse): String =
        jsonMapper
            .readTree(response.contentAsString)
            .get("error")
            .get("code")
            .asString()

    private fun memberCount(email: String): Int =
        requireNotNull(
            jdbcTemplate.queryForObject("select count(*) from member where email = ?", Int::class.java, email),
        )

    private fun uniqueEmail(): String = "race-${UUID.randomUUID()}@example.com"

    companion object {
        private const val KAKAO_REDIRECT = "http://localhost:3000/api/auth/oauth/kakao/callback"
    }
}
