package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.member.domain.OAuthProvider
import com.ogu.member.infrastructure.oauth.OAuthProviderClient
import com.ogu.member.infrastructure.oauth.OAuthUserInfo
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
import com.ogu.support.WithdrawalFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

/**
 * 탈퇴한 뒤 같은 계정으로 다시 가입한다(013 US4, research R1). 회원 행의 이메일과 닉네임 키가 비고 외부 계정 연결이
 * 지워져, 가입과 온보딩의 지금 규칙이 고치지 않아도 받아 준다. 새 회원은 탈퇴한 회원과 이어지지 않는다.
 * 제공자 호출은 `OAuthLoginApiTests`처럼 가짜 [OAuthProviderClient] 빈이 대신한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WithdrawalRejoinTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @MockitoBean(name = "kakaoClient")
    lateinit var kakaoClient: OAuthProviderClient

    @MockitoBean(name = "googleClient")
    lateinit var googleClient: OAuthProviderClient

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var safety: SafetyFixture
    private lateinit var withdrawal: WithdrawalFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        withdrawal = WithdrawalFixture(mockMvc, jdbcTemplate)
        `when`(kakaoClient.provider).thenReturn(OAuthProvider.KAKAO)
        `when`(googleClient.provider).thenReturn(OAuthProvider.GOOGLE)
    }

    @Test
    fun `US4-AC1 US4-AC3 같은 이메일로 다시 가입하고 쓰던 닉네임을 다시 쓴다`() {
        val nickname = withdrawal.uniqueNickname()
        val before = withdrawal.onboarded(nickname = nickname)
        withdrawal.withdrawAndClean(before.member)

        val after = withdrawal.signUp(before.email)

        assertThat(after.member.id).isNotEqualTo(before.member.id)
        assertThat(withdrawal.memberRow(after.member.id)["onboarded_at"]).isNull()
        withdrawal.onboard(after, nickname).andExpect(status().isOk)
        withdrawal.login(before.email).andExpect(status().isOk)
    }

    @Test
    fun `US4-AC2 같은 외부 계정으로 로그인하면 새 회원이 되어 온보딩부터 시작한다`() {
        val kakaoId = UUID.randomUUID().toString()
        val info = OAuthUserInfo(kakaoId, null, emailVerified = false)
        `when`(kakaoClient.exchange("first", KAKAO_REDIRECT, null)).thenReturn(info)
        `when`(kakaoClient.exchange("second", KAKAO_REDIRECT, null)).thenReturn(info)
        val before = member(oauth("first").andExpect(status().isOk))

        withdrawal.withdrawAndClean(before)
        assertThat(withdrawal.count("select count(*) from oauth_identity where provider_user_id = ?", kakaoId)).isZero()

        val result =
            oauth("second")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.newMember").value(true))
                .andExpect(jsonPath("$.data.member.onboarded").value(false))
        assertThat(member(result).id).isNotEqualTo(before.id)
    }

    @Test
    fun `US4-AC4 다시 가입한 회원의 글과 댓글과 알림과 도감은 비어 있다`() {
        val before = withdrawal.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithMonster(before.member)
        loop.comment(fan, postId)
        loop.comment(before.member, loop.postWithMonster(fan))
        withdrawal.withdrawAndClean(before.member)

        val after = withdrawal.onboarded(before.email).member

        val empty =
            listOf(
                "/api/v1/members/me/posts",
                "/api/v1/members/me/comments",
                "/api/v1/members/me/liked-posts",
                "/api/v1/members/me/monster-cards",
                "/api/v1/notifications",
                "/api/v1/members/me/weekly-reports",
            )
        empty.forEach { path -> assertThat(safety.data(after, path).get("items")).`as`(path).isEmpty() }
    }

    @Test
    fun `US4-AC5 예전에 공감한 글에 새 회원으로 다시 공감하고 HP도 한 번 더 준다`() {
        val before = withdrawal.onboarded()
        val postId = loop.postWithMonster(members.onboarded())
        loop.likePost(before.member, postId).andExpect(status().isOk)
        loop.comment(before.member, postId)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(10 - 1 - 3)
        withdrawal.withdrawAndClean(before.member)

        val after = withdrawal.onboarded(before.email).member
        loop.likePost(after, postId).andExpect(status().isOk)
        loop.comment(after, postId)

        assertThat(withdrawal.count("select like_count from posts where id = ?", postId)).isEqualTo(2)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(10 - 1 - 3 - 1 - 3)
    }

    private fun oauth(code: String): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/oauth/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(mapOf("code" to code, "redirectUri" to KAKAO_REDIRECT))),
        )

    private fun member(result: ResultActions): TestMember {
        val data = withdrawal.data(result)
        return TestMember(data.get("member").get("id").asLong(), data.get("tokens").get("accessToken").asString())
    }

    private companion object {
        const val KAKAO_REDIRECT = "http://localhost:3000/api/auth/oauth/kakao/callback"
    }
}
