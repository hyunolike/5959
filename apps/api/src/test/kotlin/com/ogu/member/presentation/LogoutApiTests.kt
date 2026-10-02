package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.member.application.IssuedTokens
import com.ogu.member.application.SessionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T043: `POST /api/v1/auth/logout` (US2-AC5, FR-011, SC-004)
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LogoutApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var sessionService: SessionService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    fun `US2-AC5 로그아웃하면 204이고 같은 access 토큰으로 보호 API를 부르면 401 SESSION_EXPIRED`() {
        val tokens = issue(onboarded = true)
        getMe(tokens.accessToken).andExpect(status().isOk)

        logout(tokens.accessToken)
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))

        getMe(tokens.accessToken)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"))
        val revoked =
            jdbcTemplate.queryForMap(
                "select revoked_at, revoke_reason from auth_session where id = ?",
                tokens.sessionId,
            )
        assertThat(revoked["revoked_at"]).isNotNull()
        assertThat(revoked["revoke_reason"]).isEqualTo("LOGOUT")
    }

    @Test
    fun `온보딩 전 회원도 로그아웃할 수 있다`() {
        val tokens = issue(onboarded = false)

        logout(tokens.accessToken).andExpect(status().isNoContent)

        getMe(tokens.accessToken)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"))
    }

    @Test
    fun `로그아웃은 그 세션만 무효로 하고 같은 회원의 다른 세션은 그대로다`() {
        val memberId = insertMember()
        val first = sessionService.issue(memberId = memberId, onboarded = true)
        val second = sessionService.issue(memberId = memberId, onboarded = true)

        logout(first.accessToken).andExpect(status().isNoContent)

        getMe(second.accessToken).andExpect(status().isOk)
    }

    @Test
    fun `이미 무효인 세션의 토큰으로 로그아웃하면 401 SESSION_EXPIRED`() {
        val tokens = issue(onboarded = true)
        logout(tokens.accessToken).andExpect(status().isNoContent)

        logout(tokens.accessToken)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"))
    }

    @Test
    fun `토큰 없이 로그아웃하면 401 UNAUTHORIZED`() {
        mockMvc
            .perform(post("/api/v1/auth/logout"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
    }

    private fun issue(onboarded: Boolean): IssuedTokens {
        val memberId = insertMember()
        if (onboarded) {
            jdbcTemplate.update(
                """
                update member set nickname = ?, nickname_key = ?, job_role = 'DEVELOPMENT', career_year = 'YEAR_1',
                  onboarded_at = now() where id = ?
                """.trimIndent(),
                "n$memberId",
                "n$memberId",
                memberId,
            )
        }
        return sessionService.issue(memberId = memberId, onboarded = onboarded)
    }

    private fun insertMember(): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "insert into member (auth_method, created_at, updated_at) values ('KAKAO', now(), now()) returning id",
                Long::class.java,
            ),
        )

    private fun logout(accessToken: String): ResultActions =
        mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))

    private fun getMe(accessToken: String): ResultActions =
        mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
}
