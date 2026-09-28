package com.ogu.member.security

import com.ogu.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * 운영처럼 springdoc.api-docs.enabled=false이면 /v3/api-docs는 공개 경로가 아니다.
 */
@SpringBootTest(properties = ["springdoc.api-docs.enabled=false"])
@Import(TestcontainersConfiguration::class)
class ApiDocsDisabledSecurityTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Test
    fun `springdoc api-docs가 꺼져 있으면 v3 api-docs는 토큰 없이 401이다`() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
        mockMvc
            .perform(get("/v3/api-docs/swagger-config"))
            .andExpect(status().isUnauthorized)
    }
}
