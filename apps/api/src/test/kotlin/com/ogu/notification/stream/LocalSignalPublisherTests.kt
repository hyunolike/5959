package com.ogu.notification.stream

import com.ogu.TestcontainersConfiguration
import com.ogu.member.MemberSessionsEnded
import com.ogu.monster.MonsterApi
import com.ogu.notification.NotificationSignalRecorder
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import com.ogu.support.TestMember
import com.ogu.support.WithdrawalFixture
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import kotlin.random.Random

/**
 * 018 T025(US3): 알림 신호는 서버 안에서 커밋 뒤에만 허브로 간다(research R4). 받은 신호는 [NotificationSignalRecorder]가
 * 허브와 같은 자리에서 모은다. 안전망 주기는 길게 둬서 알림은 신호로만 갈 수 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
// 이 클래스만의 설정이라 다른 테스트와 컨텍스트를 나누지 않는다. 끝나면 닫아 컨테이너와 메모리를 돌려준다
@DirtiesContext
@TestPropertySource(properties = ["ogu.notification.safety-drain-interval=10m"])
class LocalSignalPublisherTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var publisher: LocalSignalPublisher

    @Autowired
    lateinit var signals: NotificationSignalRecorder

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var hub: SseHub

    @LocalServerPort
    var port: Int = 0

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var tickets: StreamTickets
    private lateinit var withdrawal: WithdrawalFixture
    private val streams = mutableListOf<SseStream>()

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        tickets = StreamTickets(mockMvc)
        withdrawal = WithdrawalFixture(mockMvc, jdbcTemplate)
    }

    @AfterEach
    fun tearDown() {
        streams.forEach(SseStream::close)
    }

    @Test
    fun `US3-AC1 댓글이 커밋되면 글쓴이의 연결에 5초 안에 알림이 간다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val stream = open(author)

        val commentId = loop.comment(commenter, postId)

        val received = stream.awaitNotifications(1, Duration.ofSeconds(DELIVERY_SECONDS)).single()
        assertThat(
            received
                .json()
                .get("notification")
                .get("commentId")
                .asLong(),
        ).isEqualTo(commentId)
        assertThat(signals.newFor(author.id).map { it.seq }).contains(received.id!!.toLong())
    }

    @Test
    fun `트랜잭션 안에서 보내면 커밋 전에는 가지 않고 커밋 뒤에 간다`() {
        val memberId = unusedMemberId()
        val marker = unusedMemberId()

        transactionTemplate.executeWithoutResult {
            publisher.publish(NotificationSignal.New(memberId, seq = 3L))

            // 트랜잭션이 없는 다른 스레드에서 보낸 표지는 바로 간다. 그때까지도 앞의 신호는 오지 않아야 한다
            Thread.ofPlatform().start { publisher.publish(NotificationSignal.Read(marker)) }.join()
            await().atMost(AWAIT).until { signals.of(marker).isNotEmpty() }
            assertThat(signals.of(memberId)).isEmpty()
        }

        await().atMost(AWAIT).until { signals.of(memberId) == listOf(NotificationSignal.New(memberId, 3L)) }
    }

    @Test
    fun `롤백되면 보내지 않는다`() {
        val rolledBack = unusedMemberId()
        val marker = unusedMemberId()

        transactionTemplate.executeWithoutResult { status ->
            publisher.publish(NotificationSignal.Read(rolledBack))
            status.setRollbackOnly()
        }

        // 신호는 한 줄로 차례대로 가므로 뒤에 보낸 신호가 닿았으면 앞의 신호는 영영 오지 않는다
        publisher.publish(NotificationSignal.Read(marker))
        await().atMost(AWAIT).until { signals.of(marker).isNotEmpty() }
        assertThat(signals.of(rolledBack)).isEmpty()
    }

    @Test
    fun `US3-AC7 비밀번호를 바꾸거나 탈퇴하면 그 회원의 연결이 닫힌다`() {
        val changed = members.onboarded()
        val leaver = members.onboarded()
        val other = members.onboarded()
        val changedStream = open(changed)
        val leaverStream = open(leaver)
        open(other)

        // 비밀번호를 바꾸면 회원 모듈이 이 이벤트를 커밋과 함께 낸다(PasswordResetApiTests US2-AC7)
        transactionTemplate.executeWithoutResult { events.publishEvent(MemberSessionsEnded(changed.id)) }
        withdrawal.withdraw(leaver).andExpect(status().isNoContent)

        changedStream.awaitEnded()
        leaverStream.awaitEnded()
        await().atMost(AWAIT).until { hub.connectionCount(changed.id) == 0 && hub.connectionCount(leaver.id) == 0 }
        assertThat(hub.connectionCount(other.id)).isEqualTo(1)
    }

    private fun open(member: TestMember): SseStream {
        val stream = SseTestClient.connect(port, tickets.issue(member))
        streams += stream
        return stream
    }

    /** 실제 회원과 겹치지 않는 ID. 같은 컨텍스트의 다른 테스트가 받은 신호와 섞이지 않는다. */
    private fun unusedMemberId(): Long = Random.nextLong(UNUSED_FROM, Long.MAX_VALUE)

    private companion object {
        const val DELIVERY_SECONDS = 5L
        const val UNUSED_FROM = 1_000_000_000_000L
        val AWAIT: Duration = Duration.ofSeconds(5)
    }
}
