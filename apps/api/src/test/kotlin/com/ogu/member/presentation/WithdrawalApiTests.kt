package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.support.MemberFixture
import com.ogu.support.TestMember
import com.ogu.support.WithdrawalFixture
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * `POST /api/v1/members/me/withdrawal`(013 US1, research R1, R6, R9). 회원 행이 비워지고, 그 회원의 모든 세션이 끝나고,
 * 같은 계정으로 다시 로그인할 수 없는지를 본다. 글과 댓글이 사라지는 것은 `WithdrawnContentTests`가 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WithdrawalApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var withdrawal: WithdrawalFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        withdrawal = WithdrawalFixture(mockMvc, jdbcTemplate)
    }

    @Test
    fun `US1-AC3 확인이 없으면 400이고 계정은 그대로다`() {
        val member = members.onboarded()

        withdrawal
            .withdraw(member, mapOf("confirmed" to false))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        withdrawal.withdraw(member, emptyMap()).andExpect(status().isBadRequest)

        me(member).andExpect(status().isOk)
        assertThat(withdrawal.memberRow(member.id)["withdrawn_at"]).isNull()
    }

    @Test
    fun `US1-AC4 탈퇴하면 204이고 회원 행에는 번호와 탈퇴한 때만 남는다`() {
        val account = withdrawal.onboarded()

        withdrawal.withdraw(account.member).andExpect(status().isNoContent)

        // US2-AC8: 이메일, 비밀번호, 닉네임, 직군, 경력이 저장된 것까지 지워진다
        val row = withdrawal.memberRow(account.member.id)
        assertThat(row["withdrawn_at"]).isNotNull()
        assertThat(row["auth_method"]).isEqualTo("EMAIL")
        val erased = listOf("email", "password_hash", "nickname", "nickname_key", "job_role", "career_year")
        assertThat(erased.map { row[it] }).containsOnlyNulls()
        assertThat(row["onboarded_at"]).isNull()
        assertThat(sessionCount(account.member.id)).isZero()
    }

    @Test
    fun `US1-AC5 탈퇴한 계정으로 로그인하면 없는 계정과 같은 응답이다`() {
        val account = withdrawal.onboarded()
        withdrawal.withdraw(account.member).andExpect(status().isNoContent)

        val withdrawn =
            withdrawal
                .login(account.email)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn()
        val unknown = withdrawal.login("nobody-${UUID.randomUUID()}@example.com").andReturn()

        assertThat(withdrawn.response.contentAsString).isEqualTo(unknown.response.contentAsString)
    }

    @Test
    fun `US1-AC6 탈퇴하면 다른 기기의 토큰도 401이고 refresh도 되지 않는다`() {
        val account = withdrawal.onboarded()
        val otherDevice = withdrawal.loginAgain(account)
        me(otherDevice.member).andExpect(status().isOk)

        withdrawal.withdraw(account.member).andExpect(status().isNoContent)

        me(otherDevice.member)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"))
        me(account.member).andExpect(status().isUnauthorized)
        refresh(otherDevice.refreshToken).andExpect(status().isUnauthorized)
        // 실시간 알림의 연결 표도 받을 수 없다
        mockMvc
            .perform(post("/api/v1/notifications/stream-tickets").bearer(otherDevice.member.accessToken))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `US1-AC9 온보딩 전 회원도 탈퇴한다`() {
        val member = members.signedUp()

        withdrawal.withdraw(member).andExpect(status().isNoContent)

        assertThat(withdrawal.memberRow(member.id)["withdrawn_at"]).isNotNull()
        me(member).andExpect(status().isUnauthorized)
    }

    @Test
    fun `US1-AC11 운영자는 409이고 지정을 풀면 탈퇴한다`() {
        val operator = members.onboarded()
        jdbcTemplate.update("update member set role = 'OPERATOR' where id = ?", operator.id)

        withdrawal
            .withdraw(operator)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("OPERATOR_CANNOT_WITHDRAW"))
        assertThat(withdrawal.memberRow(operator.id)["withdrawn_at"]).isNull()
        me(operator).andExpect(status().isOk)

        jdbcTemplate.update("update member set role = 'MEMBER' where id = ?", operator.id)
        withdrawal.withdraw(operator).andExpect(status().isNoContent)
    }

    @Test
    fun `US1-AC12 토큰이 없으면 401이다`() {
        mockMvc
            .perform(
                post("/api/v1/members/me/withdrawal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirmed":true}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `US5-AC5 탈퇴한 뒤의 두 번째 요청은 401이다`() {
        val member = members.onboarded()
        withdrawal.withdraw(member).andExpect(status().isNoContent)
        val withdrawnAt = withdrawal.memberRow(member.id)["withdrawn_at"]

        withdrawal.withdraw(member).andExpect(status().isUnauthorized)

        assertThat(withdrawal.memberRow(member.id)["withdrawn_at"]).isEqualTo(withdrawnAt)
    }

    @Test
    fun `US5-AC6 정리 상태는 운영자만 보고 수와 때뿐이다`() {
        val member = members.onboarded()
        val operator = members.onboarded()
        jdbcTemplate.update("update member set role = 'OPERATOR' where id = ?", operator.id)

        cleanupStatus(member).andExpect(status().isNotFound)
        cleanupStatus(operator)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pending").isNumber)
            .andExpect(jsonPath("$.data.oldestWithdrawnAt").hasJsonPath())
            .andExpect(jsonPath("$.data.length()").value(2))
    }

    private fun me(member: TestMember) = mockMvc.perform(get("/api/v1/members/me").bearer(member.accessToken))

    private fun cleanupStatus(member: TestMember) = mockMvc.perform(get("/api/v1/operator/withdrawals/cleanup").bearer(member.accessToken))

    private fun refresh(refreshToken: String) =
        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}"""),
        )

    private fun sessionCount(memberId: Long): Int = withdrawal.count("select count(*) from auth_session where member_id = ?", memberId)
}
