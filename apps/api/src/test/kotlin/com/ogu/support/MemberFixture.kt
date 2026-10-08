package com.ogu.support

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** 테스트 회원. [accessToken]은 온보딩을 마쳤으면 `onboarded=true` 토큰이다. */
data class TestMember(
    val id: Long,
    val accessToken: String,
)

/** 가입과 온보딩을 실제 API로 거쳐 테스트 회원을 만든다(member 모듈 내부를 건드리지 않는다). */
class MemberFixture(
    private val mockMvc: MockMvc,
) {
    private val jsonMapper = JsonMapper.builder().build()

    fun signedUp(): TestMember {
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
        return TestMember(
            id = data.get("member").get("id").asLong(),
            accessToken = data.get("tokens").get("accessToken").asString(),
        )
    }

    fun onboarded(
        jobRole: String = "DEVELOPMENT",
        careerYear: String = "YEAR_3",
    ): TestMember {
        val member = signedUp()
        val body =
            mockMvc
                .perform(
                    put("/api/v1/members/me/onboarding")
                        .bearer(member.accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            jsonMapper.writeValueAsString(
                                mapOf("nickname" to uniqueNickname(), "jobRole" to jobRole, "careerYear" to careerYear),
                            ),
                        ),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return member.copy(accessToken = data(body).get("accessToken").asString())
    }

    private fun data(body: String): JsonNode = jsonMapper.readTree(body).get("data")

    /** 매번 다른 영문 소문자 8자. */
    private fun uniqueNickname(): String =
        UUID
            .randomUUID()
            .toString()
            .filter { it.isLetterOrDigit() }
            .map { 'a' + (it.digitToInt(16) % 26) }
            .take(8)
            .joinToString("")
}

fun MockHttpServletRequestBuilder.bearer(token: String): MockHttpServletRequestBuilder {
    val value = "Bearer $token"
    return header(HttpHeaders.AUTHORIZATION, value)
}
