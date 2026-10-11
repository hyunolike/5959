package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.support.MemberFixture
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 스트림을 여는 요청 스레드가 필터를 되돌아 나가는 동안 전용 스레드가 첫 이벤트를 쓰면, 두 스레드가 같은 응답 헤더를 고쳐
 * 헤더가 깨지고 그 연결은 헤더도 못 받은 채 비동기 타임아웃까지 멈춘다. 깨지는 일 자체는 드물어 기다려서는 잡히지 않으므로,
 * 가장 바깥 필터에서 "핸들러가 돌아왔을 때 응답이 이미 나갔는가"를 본다. 나갔다면 뒤따르는 쓰기는 헤더를 건드리지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class, NotificationStreamOpenRaceTest.Probe::class)
@TestPropertySource(properties = ["ogu.notification.heartbeat=10m", "ogu.notification.safety-drain-interval=10m"])
class NotificationStreamOpenRaceTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var probe: Probe

    private lateinit var members: MemberFixture
    private lateinit var tickets: StreamTickets
    private val streams = CopyOnWriteArrayList<SseStream>()

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        tickets = StreamTickets(mockMvc)
    }

    @AfterEach
    fun tearDown() {
        streams.forEach(SseStream::close)
    }

    @Test
    fun `응답 헤더와 첫 ping은 스트림을 연 요청 스레드에서 나간다`() {
        val member = members.onboarded()

        repeat(OPENS) { attempt ->
            val ticket = tickets.issue(member)
            val opened = CompletableFuture.supplyAsync { SseTestClient.connect(port, ticket).also { streams += it } }
            val stream =
                try {
                    opened.get(OPEN_LIMIT_SECONDS, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    throw AssertionError("${attempt + 1}번째 연결이 응답 헤더를 받지 못했습니다", e)
                }
            await().atMost(AWAIT).pollInterval(SseStream.POLL).until { stream.events.any { it.event == "ping" } }
            stream.close()
        }

        assertThat(probe.uncommitted.get()).isZero()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Probe {
        val uncommitted = AtomicInteger()

        @Bean
        fun commitProbe(): FilterRegistrationBean<OncePerRequestFilter> =
            FilterRegistrationBean<OncePerRequestFilter>(CommitProbe(uncommitted)).apply {
                order = Ordered.HIGHEST_PRECEDENCE
                addUrlPatterns(SseTestClient.STREAM_PATH)
            }
    }

    private class CommitProbe(
        private val uncommitted: AtomicInteger,
    ) : OncePerRequestFilter() {
        override fun doFilterInternal(
            request: HttpServletRequest,
            response: HttpServletResponse,
            filterChain: FilterChain,
        ) {
            filterChain.doFilter(request, response)
            if (!response.isCommitted) uncommitted.incrementAndGet()
        }

        // 비동기 디스패치(타임아웃, 완료)는 세지 않는다
        override fun shouldNotFilterAsyncDispatch(): Boolean = true
    }

    private companion object {
        const val OPENS = 50
        const val OPEN_LIMIT_SECONDS = 5L
        val AWAIT: Duration = Duration.ofSeconds(5)
    }
}
