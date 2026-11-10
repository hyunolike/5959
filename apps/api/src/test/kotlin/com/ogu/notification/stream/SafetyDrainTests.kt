package com.ogu.notification.stream

import com.ogu.TestcontainersConfiguration
import com.ogu.notification.NotificationTestSupport
import com.ogu.notification.application.NotificationProperties
import com.ogu.notification.domain.NotificationSequenceRepository
import com.ogu.shared.schedule.ActivityGate
import com.ogu.support.MemberFixture
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import kotlin.random.Random

/**
 * 018 T026(US3, research R3, R4): 안전망 따라잡기는 회원이 최근에 쓴 동안([ActivityGate])만 돈다. 신호 없이 SQL로 넣은
 * 알림은 안전망만 보낼 수 있다. 주기는 테스트에서 줄이고, 회원이 떠난 상태는 활동 창을 비활성으로 바꿔 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
// 이 클래스만의 설정이라 다른 테스트와 컨텍스트를 나누지 않는다. 끝나면 닫아 컨테이너와 메모리를 돌려준다
@DirtiesContext
@TestPropertySource(properties = ["ogu.notification.safety-drain-interval=200ms"])
class SafetyDrainTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @MockitoSpyBean
    lateinit var activity: ActivityGate

    @MockitoSpyBean
    lateinit var sequences: NotificationSequenceRepository

    @LocalServerPort
    var port: Int = 0

    private lateinit var members: MemberFixture
    private lateinit var tickets: StreamTickets
    private lateinit var support: NotificationTestSupport
    private val streams = mutableListOf<SseStream>()

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        tickets = StreamTickets(mockMvc)
        support = NotificationTestSupport(jdbcTemplate)
    }

    @AfterEach
    fun tearDown() {
        streams.forEach(SseStream::close)
    }

    @Test
    fun `회원 요청이 4분 넘게 없으면 안전망이 쿼리를 내지 않는다`() {
        val member = members.onboarded()
        val stream = open(member)
        doReturn(false).`when`(activity).isActive()
        // 막 올라간 따라잡기가 끝나기를 기다린 뒤부터 센다
        Thread.sleep(SETTLE.toMillis())
        clearInvocations(sequences)

        support.seed(member.id, postId = Random.nextLong(1, Long.MAX_VALUE / 2), seq = support.lastSeq(member.id) + 1)
        Thread.sleep(IDLE.toMillis())

        assertThat(drainQueries()).isZero()
        assertThat(stream.notifications()).isEmpty()
    }

    @Test
    fun `요청이 있는 동안 빠진 알림을 60초 안에 보낸다`() {
        val member = members.onboarded()
        val stream = open(member)
        doReturn(true).`when`(activity).isActive()
        val seq = support.lastSeq(member.id) + 1

        support.seed(member.id, postId = Random.nextLong(1, Long.MAX_VALUE / 2), seq = seq)

        // 신호 없이 넣은 알림이라 안전망만 보낼 수 있다. 운영 주기는 60초다
        assertThat(NotificationProperties().safetyDrainInterval).isEqualTo(Duration.ofSeconds(SAFETY_SECONDS))
        assertThat(stream.awaitNotifications(1, DELIVERY).single().id).isEqualTo(seq.toString())
        assertThat(drainQueries()).isPositive()
    }

    private fun open(member: TestMember): SseStream {
        val stream = SseTestClient.connect(port, tickets.issue(member))
        streams += stream
        return stream
    }

    /** 안전망이 회원별 마지막 번호를 읽은 횟수. 연결이 있으면 주기마다 한 번이다. */
    private fun drainQueries(): Int = mockingDetails(sequences).invocations.count { it.method.name == "currentOf" }

    private companion object {
        const val SAFETY_SECONDS = 60L
        val SETTLE: Duration = Duration.ofMillis(500)
        val IDLE: Duration = Duration.ofSeconds(2)
        val DELIVERY: Duration = Duration.ofSeconds(5)
    }
}
