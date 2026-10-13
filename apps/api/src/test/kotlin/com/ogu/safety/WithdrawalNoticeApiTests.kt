package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
import com.ogu.support.WithdrawalFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * `GET /api/v1/safety/withdrawal-notice`(013 US1-AC10, research R10). 최근 30일에 위기로 판정된 내 글이나 댓글이 있으면
 * 탈퇴 화면이 도움받을 곳을 함께 보인다. 탈퇴를 막지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WithdrawalNoticeApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

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
    }

    @Test
    fun `US1-AC10 최근에 위기로 판정된 글이 있으면 true이고 탈퇴는 그대로 된다`() {
        val member = members.onboarded()
        loop.createPost(member, SafetyFixture.CRISIS_TEXT)

        assertThat(recentCrisis(member)).isTrue()
        withdrawal.withdraw(member).andExpect(status().isNoContent)
    }

    @Test
    fun `US1-AC10 위기로 판정된 댓글도 센다`() {
        val member = members.onboarded()
        val postId = loop.postWithoutMonster(members.onboarded())
        loop.comment(member, postId, SafetyFixture.CRISIS_TEXT)

        assertThat(recentCrisis(member)).isTrue()
    }

    @Test
    fun `US1-AC10 위기 판정이 없거나 우려이거나 남의 것이면 false다`() {
        val member = members.onboarded()
        val other = members.onboarded()
        loop.createPost(member, SafetyFixture.SAFE_TEXT)
        loop.createPost(member, SafetyFixture.CONCERN_TEXT)
        loop.createPost(other, SafetyFixture.CRISIS_TEXT)

        assertThat(recentCrisis(member)).isFalse()
    }

    @Test
    fun `US1-AC10 30일이 지난 위기 판정은 세지 않는다`() {
        val member = members.onboarded()
        loop.createPost(member, SafetyFixture.CRISIS_TEXT)
        jdbcTemplate.update(
            "update risk_assessment set created_at = now() - interval '31 days' where author_id = ?",
            member.id,
        )

        assertThat(recentCrisis(member)).isFalse()
    }

    @Test
    fun `온보딩 전 회원에게는 403이고 토큰이 없으면 401이다`() {
        val member = members.signedUp()

        safety.get(member, PATH).andExpect(status().isForbidden)
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized)
    }

    private fun recentCrisis(member: TestMember): Boolean = safety.data(member, PATH).get("recentCrisis").asBoolean()

    private companion object {
        const val PATH = "/api/v1/safety/withdrawal-notice"
    }
}
