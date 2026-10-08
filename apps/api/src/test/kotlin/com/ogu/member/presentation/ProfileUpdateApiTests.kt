package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.member.application.ProfileService
import com.ogu.monster.MonsterApi
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
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

/** T060: 프로필 수정(004 US5, FR-013, research R13). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ProfileUpdateApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var profileService: ProfileService

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
    }

    @Test
    fun `US5-AC1 규칙에 맞는 닉네임, 직군, 경력으로 고치면 200 MemberProfile이고 GET me에 바로 보인다`() {
        val member = members.onboarded(jobRole = "DEVELOPMENT", careerYear = "YEAR_3")
        val nickname = uniqueNickname()

        update(member, mapOf("nickname" to "  $nickname  ", "jobRole" to "DESIGN", "careerYear" to "YEAR_5"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(member.id))
            .andExpect(jsonPath("$.data.nickname").value(nickname))
            .andExpect(jsonPath("$.data.jobRole").value("DESIGN"))
            .andExpect(jsonPath("$.data.careerYear").value("YEAR_5"))
            .andExpect(jsonPath("$.data.onboarded").value(true))

        me(member)
            .andExpect(jsonPath("$.data.nickname").value(nickname))
            .andExpect(jsonPath("$.data.jobRole").value("DESIGN"))
            .andExpect(jsonPath("$.data.careerYear").value("YEAR_5"))
    }

    @Test
    fun `보낸 항목만 바꾸고 나머지는 그대로 둔다`() {
        val member = members.onboarded(jobRole = "DEVELOPMENT", careerYear = "YEAR_3")
        val before = nicknameOf(member)

        update(member, mapOf("careerYear" to "YEAR_4")).andExpect(status().isOk)
        // null로 보낸 항목은 보내지 않은 것과 같다
        update(member, mapOf("nickname" to null, "jobRole" to "HR")).andExpect(status().isOk)

        me(member)
            .andExpect(jsonPath("$.data.nickname").value(before))
            .andExpect(jsonPath("$.data.jobRole").value("HR"))
            .andExpect(jsonPath("$.data.careerYear").value("YEAR_4"))
    }

    @Test
    fun `US5-AC2 다른 회원의 닉네임을 대소문자만 바꿔 넣으면 409 NICKNAME_TAKEN`() {
        val member = members.onboarded()
        val other = members.onboarded()
        val before = nicknameOf(member)

        update(member, mapOf("nickname" to nicknameOf(other).uppercase(), "jobRole" to "HR"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("NICKNAME_TAKEN"))

        // 함께 보낸 직군도 저장되지 않는다
        me(member)
            .andExpect(jsonPath("$.data.nickname").value(before))
            .andExpect(jsonPath("$.data.jobRole").value("DEVELOPMENT"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "오 구", "ogu!", "오구😀", "열한글자가넘는닉네임이다", "ㅇㄱ"])
    fun `US5-AC2 허용되지 않는 닉네임은 M1과 같은 메시지의 400 INVALID_REQUEST`(nickname: String) {
        val member = members.onboarded()
        val before = nicknameOf(member)

        update(member, mapOf("nickname" to nickname))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.message").value("닉네임은 한글, 영문, 숫자로 1~10자까지 쓸 수 있습니다."))

        assertThat(nicknameOf(member)).isEqualTo(before)
    }

    @Test
    fun `내 닉네임의 대소문자만 바꾸는 것은 허용한다`() {
        val member = members.onboarded()
        val upper = nicknameOf(member).uppercase()

        update(member, mapOf("nickname" to upper))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value(upper))
        // 같은 값을 다시 저장해도 된다
        update(member, mapOf("nickname" to upper)).andExpect(status().isOk)
    }

    @Test
    fun `필드가 하나도 없거나 직군, 경력이 목록에 없는 값이면 400`() {
        val member = members.onboarded()

        listOf(
            "{}",
            """{"nickname":null,"jobRole":null,"careerYear":null}""",
            """{"jobRole":"ASTRONAUT"}""",
            """{"careerYear":"YEAR_99"}""",
        ).forEach { body ->
            mockMvc
                .perform(
                    patch(ME).bearer(member.accessToken).contentType(MediaType.APPLICATION_JSON).content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `US5-AC3 직군과 경력을 바꿔도 예전 글은 스냅숏 그대로이고 새 글부터 바뀐 값`() {
        val member = members.onboarded(jobRole = "DEVELOPMENT", careerYear = "YEAR_3")
        val oldPost = coreLoop.createPost(member, "[실패] 직군을 바꾸기 전에 쓴 글")

        update(member, mapOf("jobRole" to "PLANNING", "careerYear" to "YEAR_7_PLUS")).andExpect(status().isOk)
        val newPost = coreLoop.createPost(member, "[실패] 직군을 바꾼 뒤에 쓴 글")

        coreLoop
            .detail(member, oldPost)
            .andExpect(jsonPath("$.data.author.jobRole").value("DEVELOPMENT"))
            .andExpect(jsonPath("$.data.author.careerYear").value("YEAR_3"))
        coreLoop
            .detail(member, newPost)
            .andExpect(jsonPath("$.data.author.jobRole").value("PLANNING"))
            .andExpect(jsonPath("$.data.author.careerYear").value("YEAR_7_PLUS"))
    }

    @Test
    fun `US5-AC4 닉네임을 바꾸면 예전 글, 댓글, 알림 목록에 바뀐 닉네임이 보인다`() {
        val member = members.onboarded()
        val other = members.onboarded()
        val myPost = coreLoop.createPost(member, "[실패] 닉네임을 바꾸기 전에 쓴 글")
        val othersPost = coreLoop.createPost(other, "[실패] 남의 글")
        coreLoop.comment(member, othersPost, "닉네임을 바꾸기 전에 단 댓글")
        // 글쓴이에게 댓글 알림이 만들어질 때까지 기다린다
        await().atMost(CoreLoopFixture.AWAIT_LIMIT).pollInterval(CoreLoopFixture.POLL).until {
            notifications(other).get("items").size() == 1
        }
        val renamed = uniqueNickname()

        update(member, mapOf("nickname" to renamed)).andExpect(status().isOk)

        coreLoop.detail(other, myPost).andExpect(jsonPath("$.data.author.nickname").value(renamed))
        coreLoop
            .comments(other, othersPost)
            .andExpect(jsonPath("$.data.items[0].author.nickname").value(renamed))
        val notification = notifications(other).get("items").get(0)
        assertThat(notification.get("type").asString()).isEqualTo("POST_COMMENT")
        assertThat(notification.get("actor").get("nickname").asString()).isEqualTo(renamed)
    }

    @Test
    fun `두 회원이 같은 닉네임을 동시에 저장하면 한 명만 성공한다`() {
        val contenders = listOf(members.onboarded(), members.onboarded())
        val nickname = uniqueNickname()
        val ready = CountDownLatch(contenders.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(contenders.size)
        try {
            val futures =
                contenders.mapIndexed { index, member ->
                    // 대소문자만 다르게 보내 nickname_key 유일 제약으로 판정되는지도 함께 본다
                    val requested = if (index == 0) nickname else nickname.uppercase()
                    executor.submit(
                        Callable {
                            ready.countDown()
                            start.await()
                            update(member, mapOf("nickname" to requested)).andReturn().response
                        },
                    )
                }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val responses = futures.map { it.get(20, TimeUnit.SECONDS) }

            assertThat(responses.map { it.status }).containsExactlyInAnyOrder(200, 409)
            val rejected = responses.first { it.status == 409 }
            assertThat(
                jsonMapper
                    .readTree(rejected.contentAsString)
                    .get("error")
                    .get("code")
                    .asString(),
            ).isEqualTo("NICKNAME_TAKEN")
        } finally {
            executor.shutdownNow()
        }
        val holders =
            jdbcTemplate.queryForObject(
                "select count(*) from member where nickname_key = ?",
                Int::class.java,
                nickname.lowercase(),
            )
        assertThat(holders).isEqualTo(1)
    }

    @Test
    fun `온보딩 전 회원의 수정은 서비스에서도 403 ONBOARDING_REQUIRED`() {
        val signedUp = members.signedUp()

        // 필터(OnboardingGuard)를 거치지 않고 서비스를 바로 불러도 막힌다
        assertThatThrownBy { profileService.update(signedUp.id, uniqueNickname(), null, null) }
            .isInstanceOfSatisfying(BusinessException::class.java) {
                assertThat(it.errorCode).isEqualTo(ErrorCode.ONBOARDING_REQUIRED)
            }
        update(signedUp, mapOf("jobRole" to "HR"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        me(signedUp).andExpect(jsonPath("$.data.onboarded").value(false)).andExpect(jsonPath("$.data.jobRole").isEmpty)
    }

    @Test
    fun `토큰 없이 부르면 401`() {
        mockMvc
            .perform(patch(ME).contentType(MediaType.APPLICATION_JSON).content("""{"jobRole":"HR"}"""))
            .andExpect(status().isUnauthorized)
    }

    private fun update(
        member: TestMember,
        body: Map<String, Any?>,
    ): ResultActions =
        mockMvc.perform(
            patch(ME)
                .bearer(member.accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)),
        )

    private fun me(member: TestMember): ResultActions {
        val request = get(ME).bearer(member.accessToken)
        return mockMvc.perform(request).andExpect(status().isOk)
    }

    private fun notifications(member: TestMember) =
        jsonMapper
            .readTree(
                mockMvc
                    .perform(get("/api/v1/notifications").bearer(member.accessToken))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            ).get("data")

    private fun nicknameOf(member: TestMember): String =
        jdbcTemplate.queryForObject("select nickname from member where id = ?", String::class.java, member.id)!!

    /** 매번 다른 영문 소문자 8자. */
    private fun uniqueNickname(): String =
        UUID
            .randomUUID()
            .toString()
            .filter { it.isLetterOrDigit() }
            .map { 'a' + (it.digitToInt(16) % 26) }
            .take(8)
            .joinToString("")

    private companion object {
        const val ME = "/api/v1/members/me"
    }
}
