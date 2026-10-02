package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.member.infrastructure.security.BffClientIpResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 로그인 실패 제한의 동시성(FR-004, research R6). 시도를 비밀번호 검증 전에 세므로, 한꺼번에 들어온 요청도
 * 한도만큼만 bcrypt에 닿는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LoginConcurrencyApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var authProperties: AuthProperties

    @MockitoSpyBean
    lateinit var passwordEncoder: PasswordEncoder

    private val jsonMapper = JsonMapper.builder().build()

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    fun `한 IP에서 한 이메일로 틀린 비밀번호 요청 20건이 동시에 오면 정확히 5건만 비밀번호를 검증하고 나머지는 429`() {
        val email = "user-${UUID.randomUUID()}@example.com"
        signup(email)
        val ip = "203.0.113.${(1..254).random()}"
        clearInvocations(passwordEncoder)

        val statuses = runConcurrently(20) { login(email, "wrongpass123", ip) }

        assertThat(statuses.count { it == 401 }).isEqualTo(5)
        assertThat(statuses.count { it == 429 }).isEqualTo(15)
        verify(passwordEncoder, times(5)).matches(any(), anyString())
    }

    private fun runConcurrently(
        threads: Int,
        action: () -> Int,
    ): List<Int> {
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    executor.submit(
                        Callable {
                            ready.countDown()
                            start.await()
                            action()
                        },
                    )
                }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun login(
        email: String,
        password: String,
        clientIp: String,
    ): Int =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .header(BffClientIpResolver.BFF_KEY_HEADER, authProperties.bffKey)
                    .header(BffClientIpResolver.CLIENT_IP_HEADER, clientIp)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(mapOf("email" to email, "password" to password))),
            ).andReturn()
            .response.status

    private fun signup(email: String) {
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(mapOf("email" to email, "password" to VALID_PASSWORD))),
            ).andExpect(status().isCreated)
    }

    companion object {
        private const val VALID_PASSWORD = "password123"
    }
}
