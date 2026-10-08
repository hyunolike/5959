package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SseEvent
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T023: 끊긴 동안의 알림 재전송(SC-002, US1-AC6, research R4). 다시 붙으면 `lastEventId` 뒤의 알림을 DB에서 번호 순서로
 * 받는다. 빠짐도 중복도 없는지는 받은 것 전체를 DB 구간과 비교해 본다. 마지막에는 표지 알림을 하나 더 만들어, 그것이 올
 * 때까지 뒤늦은 중복이 없는지도 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
// 이 클래스만의 설정이라 다른 테스트와 컨텍스트를 나누지 않는다. 끝나면 닫아 컨테이너와 메모리를 돌려준다
@DirtiesContext
class NotificationStreamReplayTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var redis: StringRedisTemplate

    @Autowired
    lateinit var container: RedisMessageListenerContainer

    @LocalServerPort
    var port: Int = 0

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var tickets: StreamTickets
    private lateinit var support: NotificationTestSupport
    private val streams = mutableListOf<SseStream>()

    @BeforeEach
    fun setUp() {
        val mockMvc: MockMvc =
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        tickets = StreamTickets(mockMvc)
        support = NotificationTestSupport(jdbcTemplate, redis, container)
    }

    @AfterEach
    fun tearDown() {
        streams.forEach(SseStream::close)
    }

    @Test
    fun `US1-AC6 끊긴 동안 알림 50건(공감 묶음 갱신 포함)을 만들고 마지막 번호로 다시 붙으면 받은 알림 ID 집합이 DB 구간과 같고 같은 seq가 두 번 오지 않으며 seq 오름차순이다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val likers = (1..LIKES).map { members.onboarded() }
        val postId = loop.postWithoutMonster(author)
        val likedPost = loop.postWithoutMonster(author)
        val first = open(author)
        loop.comment(commenter, postId)
        val lastSeen =
            first
                .awaitNotifications(1)
                .single()
                .id!!
                .toLong()
        first.close()

        // 끊긴 동안: 댓글 40건과 같은 글 공감 10건(묶음 하나가 열 번 갱신된다)을 섞는다
        repeat(COMMENTS) { i ->
            loop.comment(commenter, postId, "댓글 $i")
            if (i % LIKE_EVERY == 0) loop.likePost(likers[i / LIKE_EVERY], likedPost).andExpect(status().isOk)
        }
        awaitNotificationsSettled(author, postId, likedPost, expectedRows = 1 + COMMENTS + 1)

        val expected = rowsAfter(author.id, lastSeen)
        val replayed = open(author, lastEventId = lastSeen)
        val received = receiveAllThenMarker(replayed, author, commenter, postId, expected.size)

        assertThat(received.map { it.id!!.toLong() }).isEqualTo(expected.map { it.seq })
        assertThat(
            received
                .map {
                    it
                        .json()
                        .get("notification")
                        .get("notificationId")
                        .asLong()
                }.toSet(),
        ).isEqualTo(expected.map { it.id }.toSet())
        assertThat(received.map { it.id }).doesNotHaveDuplicates()
        assertThat(received.map { it.id!!.toLong() }).isSorted()
        val like =
            received.single {
                it
                    .json()
                    .get("notification")
                    .get("type")
                    .asString() == "POST_LIKE"
            }
        assertThat(
            like
                .json()
                .get("notification")
                .get("actorCount")
                .asInt(),
        ).isEqualTo(LIKES)
    }

    @Test
    fun `공감 묶음은 끊긴 동안 여러 번 갱신돼도 마지막 상태로 한 번만 온다`() {
        val author = members.onboarded()
        val likers = (1..3).map { members.onboarded() }
        val postId = loop.postWithoutMonster(author)
        val start = support.lastSeq(author.id)

        likers.forEach { loop.likePost(it, postId).andExpect(status().isOk) }
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
            support.notificationsOf(author.id, "POST_LIKE").singleOrNull()?.actorCount == likers.size
        }
        support.awaitListenersIdle(postId)
        val group = support.notificationsOf(author.id, "POST_LIKE").single()

        val stream = open(author, lastEventId = start)
        val likes = receiveAllThenMarker(stream, author, likers.first(), postId, 1)

        val notification = likes.single().json().get("notification")
        assertThat(notification.get("type").asString()).isEqualTo("POST_LIKE")
        assertThat(notification.get("notificationId").asLong()).isEqualTo(group.id)
        assertThat(notification.get("actorCount").asInt()).isEqualTo(likers.size)
        assertThat(notification.get("actor").get("id").asLong()).isEqualTo(group.latestActorId)
        // 묶음은 세 번 번호를 받았지만 마지막 번호로 한 번만 온다
        assertThat(likes.single().id!!.toLong()).isEqualTo(group.seq).isEqualTo(start + likers.size)
    }

    @Test
    fun `Last-Event-ID 헤더가 쿼리 lastEventId보다 우선한다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        repeat(3) { loop.comment(fan, postId, "댓글 $it") }
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until { support.notificationsOf(author.id).size == 3 }
        val rows = support.notificationsOf(author.id)

        val stream = open(author, lastEventId = 0, headers = mapOf("Last-Event-ID" to rows[1].seq.toString()))

        val received = receiveAllThenMarker(stream, author, fan, postId, 1)
        assertThat(received.single().id).isEqualTo(rows[2].seq.toString())
    }

    @Test
    fun `lastEventId 없이 붙으면 지금의 last_seq 이후만 받는다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        repeat(2) { loop.comment(fan, postId, "지난 댓글 $it") }
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until { support.notificationsOf(author.id).size == 2 }
        support.awaitListenersIdle(postId)

        val stream = open(author)
        loop.comment(fan, postId, "새 댓글")

        val received = stream.awaitNotifications(1)
        assertThat(received.single().id).isEqualTo(support.lastSeq(author.id).toString())
        val marker = receiveAllThenMarker(stream, author, fan, postId, 1)
        assertThat(marker.map { it.id!!.toLong() }).containsExactly(3L)
    }

    @Test
    fun `90일이 지난 알림은 재전송하지 않는다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        repeat(2) { loop.comment(fan, postId, "댓글 $it") }
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until { support.notificationsOf(author.id).size == 2 }
        val rows = support.notificationsOf(author.id)
        jdbcTemplate.update(
            "update notification set created_at = now() - interval '90 days 1 second' where id = ?",
            rows.first().id,
        )

        val stream = open(author, lastEventId = 0)

        val received = receiveAllThenMarker(stream, author, fan, postId, 1)
        assertThat(received.single().id).isEqualTo(rows[1].seq.toString())
    }

    private fun open(
        member: TestMember,
        lastEventId: Long? = null,
        headers: Map<String, String> = emptyMap(),
    ): SseStream = SseTestClient.connect(port, tickets.issue(member), lastEventId, headers).also { streams += it }

    /**
     * [expected]개를 받은 뒤 표지 댓글 알림을 하나 더 만들어 그것까지 받는다. 표지 앞의 것만 돌려준다. 표지가 오기 전에
     * 뒤늦은 중복이 끼면 개수가 맞지 않는다.
     */
    private fun receiveAllThenMarker(
        stream: SseStream,
        receiver: TestMember,
        actor: TestMember,
        postId: Long,
        expected: Int,
    ): List<SseEvent> {
        stream.awaitNotifications(expected)
        val markerComment = loop.comment(actor, postId, "표지")
        val all = stream.awaitNotifications(expected + 1)
        val marker = all.last().json().get("notification")
        assertThat(marker.get("commentId").asLong()).isEqualTo(markerComment)
        assertThat(all).hasSize(expected + 1)
        assertThat(support.notificationsOf(receiver.id).last().seq).isEqualTo(all.last().id!!.toLong())
        return all.dropLast(1)
    }

    private fun awaitNotificationsSettled(
        receiver: TestMember,
        vararg postIds: Long,
        expectedRows: Int,
    ) {
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
            support.notificationsOf(receiver.id).size == expectedRows
        }
        postIds.forEach(support::awaitListenersIdle)
    }

    private fun rowsAfter(
        receiverId: Long,
        afterSeq: Long,
    ): List<NotificationRow> = support.notificationsOf(receiverId).filter { it.seq > afterSeq }

    private companion object {
        const val COMMENTS = 40
        const val LIKES = 10
        const val LIKE_EVERY = COMMENTS / LIKES
    }
}
