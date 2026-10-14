package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/** 메일 발송이 꺼진 기본 설정의 비밀번호 재설정(015 US4-AC1, AC2). 처음에는 이 상태로 나간다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PasswordResetDisabledApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    fun `US4-AC1 꺼져 있으면 로그인 없이 물어도 available이 false다`() {
        mockMvc
            .perform(get("/api/v1/auth/password-reset"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(false))
    }

    @Test
    fun `US4-AC2 꺼져 있으면 요청은 503이다`() {
        mockMvc
            .perform(
                post("/api/v1/auth/password-reset")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"someone@example.com"}"""),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_UNAVAILABLE"))
    }
}
