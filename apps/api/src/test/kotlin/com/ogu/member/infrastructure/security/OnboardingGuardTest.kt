package com.ogu.member.infrastructure.security

import com.ogu.member.AuthenticatedMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class OnboardingGuardTest {
    private val guard = OnboardingGuard(SecurityErrorWriter(JsonMapper.builder().build()))

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "GET /api/v1/members/me",
            "GET /api/v1/members/nickname-availability",
            "PUT /api/v1/members/me/onboarding",
            "POST /api/v1/auth/logout",
        ],
    )
    fun `온보딩 전 토큰도 허용 목록 경로는 통과한다`(request: String) {
        authenticate(onboarded = false)
        val (method, path) = request.split(" ")

        val result = run(method, path)

        assertThat(result.passed).isTrue()
        assertThat(result.response.status).isEqualTo(200)
    }

    @Test
    fun `온보딩 전 토큰으로 허용 목록 밖의 API를 부르면 403 ONBOARDING_REQUIRED다`() {
        authenticate(onboarded = false)

        val result = run("GET", "/api/v1/posts")

        assertThat(result.passed).isFalse()
        assertThat(result.response.status).isEqualTo(403)
        assertThat(result.response.contentType).startsWith("application/json")
        assertThat(result.response.contentAsString)
            .contains("\"success\":false")
            .contains("\"code\":\"ONBOARDING_REQUIRED\"")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["/api/v1/members/meX", "/api/v1/members/me/", "/API/V1/MEMBERS/ME"])
    fun `허용 목록과 비슷하기만 한 경로는 온보딩 전 토큰에 403 ONBOARDING_REQUIRED다`(path: String) {
        authenticate(onboarded = false)

        val result = run("GET", path)

        assertThat(result.passed).isFalse()
        assertThat(result.response.status).isEqualTo(403)
        assertThat(result.response.contentAsString).contains("\"code\":\"ONBOARDING_REQUIRED\"")
    }

    @Test
    fun `허용 목록 경로의 하위 경로는 허용 목록이 아니다`() {
        authenticate(onboarded = false)

        val result = run("GET", "/api/v1/members/me/posts")

        assertThat(result.passed).isFalse()
        assertThat(result.response.status).isEqualTo(403)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["PATCH", "PUT", "POST", "DELETE"])
    fun `온보딩 전 토큰으로 내 프로필을 GET 밖의 메서드로 부르면 403 ONBOARDING_REQUIRED다`(method: String) {
        authenticate(onboarded = false)

        val result = run(method, "/api/v1/members/me")

        assertThat(result.passed).isFalse()
        assertThat(result.response.status).isEqualTo(403)
        assertThat(result.response.contentAsString).contains("\"code\":\"ONBOARDING_REQUIRED\"")
    }

    @Test
    fun `온보딩을 마친 토큰은 내 프로필을 PATCH로 부를 수 있다`() {
        authenticate(onboarded = true)

        assertThat(run("PATCH", "/api/v1/members/me").passed).isTrue()
    }

    @Test
    fun `온보딩을 마친 토큰은 어느 API든 통과한다`() {
        authenticate(onboarded = true)

        assertThat(run("GET", "/api/v1/posts").passed).isTrue()
    }

    @Test
    fun `인증되지 않은 요청은 판단하지 않고 넘긴다`() {
        assertThat(run("GET", "/api/v1/posts").passed).isTrue()
    }

    private fun authenticate(onboarded: Boolean) {
        val member = AuthenticatedMember(memberId = 1L, sessionId = UUID.randomUUID(), onboarded = onboarded)
        SecurityContextHolder.getContext().authentication = MemberAuthentication(member, "token")
    }

    private fun run(
        method: String,
        path: String,
    ): GuardResult {
        val request = MockHttpServletRequest(method, path)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        guard.doFilter(request, response, chain)
        return GuardResult(passed = chain.request != null, response = response)
    }

    private data class GuardResult(
        val passed: Boolean,
        val response: MockHttpServletResponse,
    )
}
