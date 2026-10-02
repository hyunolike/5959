package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.member.application.IssuedTokens
import com.ogu.member.application.SessionService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
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

/**
 * T061: `POST /api/v1/auth/refresh`의 요청, 응답 모양. 분기별 동작은 SessionRefreshTest가 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RefreshApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var sessionService: SessionService

    private val jsonMapper = JsonMapper.builder().build()

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    fun `refresh 토큰으로 갱신하면 200이고 회원과 새 토큰을 AuthResult로 준다`() {
        val tokens = issue()

        refresh(tokens.refreshToken!!)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").doesNotExist())
            .andExpect(jsonPath("$.data.newMember").value(false))
            .andExpect(jsonPath("$.data.member.onboarded").value(false))
            .andExpect(jsonPath("$.data.tokens.accessToken").isString)
            .andExpect(jsonPath("$.data.tokens.refreshToken").isString)
            .andExpect(jsonPath("$.data.tokens.refreshTokenExpiresAt").isString)
    }

    @Test
    fun `유예 구간의 직전 토큰으로 갱신하면 tokens refreshToken이 null로 온다`() {
        val tokens = issue()
        refresh(tokens.refreshToken!!).andExpect(status().isOk)

        refresh(tokens.refreshToken!!)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.tokens.accessToken").isString)
            .andExpect(jsonPath("$.data.tokens.refreshToken").value(null as Any?))
    }

    @Test
    fun `모르는 refresh 토큰이면 401 SESSION_EXPIRED`() {
        refresh("unknown")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"))
    }

    @Test
    fun `무효화된 세션의 access 토큰이 붙어 있어도 refresh는 막히지 않는다`() {
        val tokens = issue()
        jdbcTemplate.update("update auth_session set revoked_at = now() where id = ?", tokens.sessionId)
        val other = issue()

        refresh(other.refreshToken!!, bearer = tokens.accessToken).andExpect(status().isOk)
    }

    private fun issue(): IssuedTokens {
        val memberId =
            requireNotNull(
                jdbcTemplate.queryForObject(INSERT_MEMBER, Long::class.java),
            )
        return sessionService.issue(memberId = memberId, onboarded = false)
    }

    private fun refresh(
        refreshToken: String,
        bearer: String? = null,
    ): ResultActions {
        val request =
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(mapOf("refreshToken" to refreshToken)))
        if (bearer != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
        return mockMvc.perform(request)
    }

    companion object {
        private const val INSERT_MEMBER =
            "insert into member (auth_method, created_at, updated_at) values ('KAKAO', now(), now()) returning id"
    }
}
