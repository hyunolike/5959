package com.ogu.member.security

import com.ogu.TestcontainersConfiguration
import com.ogu.member.AuthenticatedMember
import com.ogu.member.application.IssuedTokens
import com.ogu.member.application.SessionService
import com.ogu.shared.response.ApiResponse
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext

/**
 * T015~T017: 보안 필터 체인 전체(JWT 검증 → 세션 확인 → 온보딩 확인)를 실제 DB와 함께 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, SecurityIntegrationTests.ProbeController::class)
class SecurityIntegrationTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var sessionService: SessionService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    fun `토큰 없이 보호 경로를 부르면 401 UNAUTHORIZED를 ApiResponse 봉투로 받는다`() {
        mockMvc
            .perform(get(PROTECTED_PATH))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
    }

    @Test
    fun `서명이 맞지 않는 토큰으로 보호 경로를 부르면 401 UNAUTHORIZED다`() {
        val tokens = issue(onboarded = true)
        val tampered = tokens.accessToken.dropLast(2) + if (tokens.accessToken.endsWith("AA")) "BB" else "AA"

        mockMvc
            .perform(get(PROTECTED_PATH).bearer(tampered))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
    }

    @Test
    fun `유효한 세션의 토큰이면 컨트롤러가 AuthenticatedMember를 받는다`() {
        val memberId = insertMember()
        val tokens = sessionService.issue(memberId = memberId, onboarded = true)

        mockMvc
            .perform(get(PROTECTED_PATH).bearer(tokens.accessToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.memberId").value(memberId))
            .andExpect(jsonPath("$.data.sessionId").value(tokens.sessionId.toString()))
            .andExpect(jsonPath("$.data.onboarded").value(true))
    }

    @Test
    fun `무효화된 세션의 토큰으로 보호 경로를 부르면 401 SESSION_EXPIRED다`() {
        val tokens = issue(onboarded = true)
        jdbcTemplate.update(
            "update auth_session set revoked_at = now(), revoke_reason = 'LOGOUT' where id = ?",
            tokens.sessionId,
        )

        mockMvc
            .perform(get(PROTECTED_PATH).bearer(tokens.accessToken))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"))
    }

    @Test
    fun `만료된 세션의 토큰으로 보호 경로를 부르면 401 SESSION_EXPIRED다`() {
        val tokens = issue(onboarded = true)
        jdbcTemplate.update(
            "update auth_session set expires_at = now() - interval '1 second' where id = ?",
            tokens.sessionId,
        )

        mockMvc
            .perform(get(PROTECTED_PATH).bearer(tokens.accessToken))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"))
    }

    @Test
    fun `온보딩 전 토큰으로 허용 목록 밖의 보호 경로를 부르면 403 ONBOARDING_REQUIRED다`() {
        val tokens = issue(onboarded = false)

        mockMvc
            .perform(get(PROTECTED_PATH).bearer(tokens.accessToken))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
    }

    @Test
    fun `온보딩 전 토큰이라도 무효화된 세션이면 401 SESSION_EXPIRED가 먼저다`() {
        val tokens = issue(onboarded = false)
        jdbcTemplate.update("update auth_session set revoked_at = now() where id = ?", tokens.sessionId)

        mockMvc
            .perform(get(PROTECTED_PATH).bearer(tokens.accessToken))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"))
    }

    @Test
    fun `헬스 체크와 API 문서는 토큰 없이 접근할 수 있다`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
    }

    @Test
    fun `인증 공개 경로는 토큰 없이도, 무효화된 세션의 토큰이 붙어 있어도 보안 필터가 막지 않는다`() {
        val revoked = issue(onboarded = true)
        jdbcTemplate.update("update auth_session set revoked_at = now() where id = ?", revoked.sessionId)
        val publicPaths =
            listOf(
                "/api/v1/auth/signup",
                "/api/v1/auth/login",
                "/api/v1/auth/oauth/kakao",
                "/api/v1/auth/refresh",
            )

        publicPaths.forEach { path ->
            val withoutToken =
                mockMvc
                    .perform(post(path))
                    .andReturn()
                    .response.status
            val withRevokedToken =
                mockMvc
                    .perform(post(path).bearer(revoked.accessToken))
                    .andReturn()
                    .response.status
            val withGarbageToken =
                mockMvc
                    .perform(post(path).bearer("not-a-jwt"))
                    .andReturn()
                    .response.status

            assertThat(listOf(withoutToken, withRevokedToken, withGarbageToken))
                .describedAs(path)
                .doesNotContain(401, 403)
        }
    }

    @Test
    fun `API 밖의 다른 경로는 토큰 없이 접근할 수 없다`() {
        mockMvc
            .perform(get("/actuator/env"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
    }

    private fun issue(onboarded: Boolean): IssuedTokens {
        val memberId = insertMember()
        return sessionService.issue(memberId = memberId, onboarded = onboarded)
    }

    private fun insertMember(): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "insert into member (auth_method, created_at, updated_at) values ('KAKAO', now(), now()) returning id",
                Long::class.java,
            ),
        )

    private fun MockHttpServletRequestBuilder.bearer(token: String) = header(HttpHeaders.AUTHORIZATION, "Bearer $token")

    /** 테스트 전용 컨트롤러. 온보딩 허용 목록 밖에 있는 가짜 보호 경로다. */
    @RestController
    class ProbeController {
        @GetMapping(PROTECTED_PATH)
        fun probe(member: AuthenticatedMember): ApiResponse<AuthenticatedMember> = ApiResponse.success(member)
    }

    companion object {
        const val PROTECTED_PATH = "/api/v1/test-only/protected-probe"
    }
}
