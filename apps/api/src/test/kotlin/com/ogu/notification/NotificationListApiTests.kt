package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.notification.application.NotificationQueryService
import com.ogu.notification.application.NotificationWriter
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.QueryCounter
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * T038: 알림 목록 `GET /api/v1/notifications`(US2-AC1, AC2, AC5, FR-007, FR-010, research R10, R12). 알림이 많이 필요한
 * 곳은 행을 SQL로 넣고([NotificationTestSupport.seed]), 종류와 묶음은 실제 댓글과 공감으로 만든다. 보관 기간은
 * [MutableClock]을 움직여 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, NotificationListApiTests.ClockOverride::class)
// 이 클래스만의 설정이라 다른 테스트와 컨텍스트를 나누지 않는다. 끝나면 닫아 컨테이너와 메모리를 돌려준다
@DirtiesContext
class NotificationListApiTests {
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

    @Autowired
    lateinit var queries: NotificationQueryService

    @Autowired
    lateinit var writer: NotificationWriter

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var clock: MutableClock

    private val unusedIds = AtomicLong(System.nanoTime() % UNUSED_ID_RANGE)
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var support: NotificationTestSupport

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        support = NotificationTestSupport(jdbcTemplate, redis, container)
    }

    @Test
    fun `US2-AC1 최신 20개와 항목 필드(종류, 행동한 회원 닉네임, 묶인 인원 수, 글 앞부분, 시각, 읽음)`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val lateFan = members.onboarded()
        val content = "[실패] " + "가나다라마".repeat(12)
        val postId = loop.createPost(author, content)
        val seeded = (1L..SEEDED).map { support.seed(author.id, postId, seq = it, actorId = fan.id) }
        support.markRead(seeded.last())
        // 실제 댓글과 공감: 댓글(22), 공감 묶음(23), 댓글(24), 같은 묶음에 두 번째 공감(25, 맨 위로 올라온다)
        val firstComment = loop.comment(fan, postId)
        awaitCount(author, SEEDED + 1)
        loop.likePost(fan, postId).andExpect(status().isOk)
        awaitCount(author, SEEDED + 2)
        val secondComment = loop.comment(lateFan, postId)
        awaitCount(author, SEEDED + 3)
        loop.likePost(lateFan, postId).andExpect(status().isOk)
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
            support.notificationsOf(author.id, "POST_LIKE").singleOrNull()?.actorCount == 2
        }
        support.awaitListenersIdle(postId)

        val page = page(author)

        val items = page.get("items").toList()
        assertThat(items).hasSize(PAGE_SIZE)
        // 묶음의 첫 공감 번호(23)는 두 번째 공감이 25로 올리면서 비었다
        assertThat(items.map { it.get("seq").asLong() })
            .isEqualTo(listOf(SEEDED + 4, SEEDED + 3, SEEDED + 1) + (SEEDED downTo 5L))
        assertThat(page.get("nextCursor").asString()).isNotBlank()
        // 묶인 공감은 마지막 공감 기준으로 먼저 온 댓글보다 위에 있다
        val group = items[0]
        assertThat(group.get("type").asString()).isEqualTo("POST_LIKE")
        assertThat(group.get("actorCount").asInt()).isEqualTo(2)
        assertThat(group.get("actor").get("id").asLong()).isEqualTo(lateFan.id)
        assertThat(group.get("actor").get("nickname").asString()).isEqualTo(nickname(lateFan))
        assertThat(group.get("commentId").isNull).isTrue()
        assertThat(group.get("read").asBoolean()).isFalse()
        assertThat(Instant.parse(group.get("updatedAt").asString()))
            .isAfterOrEqualTo(Instant.parse(group.get("createdAt").asString()))
        val comment = items[1]
        assertThat(comment.get("type").asString()).isEqualTo("POST_COMMENT")
        assertThat(comment.get("notificationId").asLong())
            .isEqualTo(support.notificationsOf(author.id).single { it.commentId == secondComment }.id)
        assertThat(comment.get("commentId").asLong()).isEqualTo(secondComment)
        assertThat(comment.get("actorCount").asInt()).isEqualTo(1)
        assertThat(comment.get("actor").get("nickname").asString()).isEqualTo(nickname(lateFan))
        assertThat(comment.get("postId").asLong()).isEqualTo(postId)
        assertThat(comment.get("post").get("postId").asLong()).isEqualTo(postId)
        assertThat(comment.get("post").get("contentPreview").asString()).isEqualTo(content.take(PREVIEW_LENGTH))
        assertThat(comment.get("createdAt").asString()).isNotBlank()
        assertThat(items[2].get("commentId").asLong()).isEqualTo(firstComment)
        assertThat(items[2].get("actor").get("nickname").asString()).isEqualTo(nickname(fan))
        // 읽은 알림도 목록에 남고 read로 구분한다
        assertThat(items[3].get("notificationId").asLong()).isEqualTo(seeded.last())
        assertThat(items[3].get("read").asBoolean()).isTrue()
        assertThat(items[4].get("read").asBoolean()).isFalse()
        // 다른 회원의 알림은 보이지 않는다
        val others = page(fan)
        assertThat(others.get("items").toList()).isEmpty()
        assertThat(others.get("nextCursor").isNull).isTrue()
    }

    @Test
    fun `US2-AC2 커서로 다음 20개, 중복과 누락 없음`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val olderPost = loop.postWithoutMonster(author)
        val newerPost = loop.postWithoutMonster(author)
        val seeded =
            (1L..TOTAL).associateWith { seq ->
                when (seq) {
                    OLD_GROUP_SEQ -> support.seed(author.id, olderPost, seq, type = "POST_LIKE", actorId = fan.id)
                    NEW_GROUP_SEQ -> support.seed(author.id, newerPost, seq, type = "POST_LIKE", actorId = fan.id)
                    else -> support.seed(author.id, olderPost, seq, actorId = fan.id)
                }
            }
        val oldGroup = seeded.getValue(OLD_GROUP_SEQ)
        val newGroup = seeded.getValue(NEW_GROUP_SEQ)

        val first = page(author)
        // 쪽 사이에: 다음 쪽에 있던 묶음과 이미 본 묶음이 갱신되고(번호가 올라간다), 새 알림이 하나 온다
        transactionTemplate.executeWithoutResult { writer.addLike(author.id, olderPost, unusedId()) }
        transactionTemplate.executeWithoutResult { writer.addLike(author.id, newerPost, unusedId()) }
        val arrived = support.seed(author.id, olderPost, seq = TOTAL + 3, actorId = fan.id)
        val second = page(author, cursor = first.get("nextCursor").asString())
        val third = page(author, cursor = second.get("nextCursor").asString())

        val firstIds = ids(first)
        val secondIds = ids(second)
        val thirdIds = ids(third)
        assertThat(firstIds).hasSize(PAGE_SIZE)
        assertThat(secondIds).hasSize(PAGE_SIZE)
        assertThat(third.get("nextCursor").isNull).isTrue()
        val walked = firstIds + secondIds + thirdIds
        // 같은 알림이 두 번 나오지 않는다. 쪽 사이에 위로 올라간 묶음과 새 알림은 다음 쪽에 끼어들지 않는다
        assertThat(walked).doesNotHaveDuplicates()
        assertThat(secondIds + thirdIds).doesNotContain(oldGroup, newGroup, arrived)
        // 바뀌지 않은 알림은 하나도 빠지지 않는다. 올라간 묶음은 첫 쪽을 다시 읽으면 맨 위에 있다
        assertThat(walked).containsExactlyInAnyOrderElementsOf(seeded.values - oldGroup)
        assertThat(seqs(second) + seqs(third)).isEqualTo((TOTAL - PAGE_SIZE downTo 1L).filter { it != OLD_GROUP_SEQ })
        assertThat(ids(page(author)).take(3)).containsExactly(arrived, newGroup, oldGroup)
    }

    @Test
    fun `size만큼만 주고 남은 것이 없으면 nextCursor가 null이다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        (1L..3L).forEach { support.seed(author.id, postId, seq = it) }

        val exact = page(author, size = 3)
        assertThat(seqs(exact)).containsExactly(3L, 2L, 1L)
        assertThat(exact.get("nextCursor").isNull).isTrue()

        val firstTwo = page(author, size = 2)
        assertThat(seqs(firstTwo)).containsExactly(3L, 2L)
        val rest = page(author, cursor = firstTwo.get("nextCursor").asString(), size = 2)
        assertThat(seqs(rest)).containsExactly(1L)
        assertThat(rest.get("nextCursor").isNull).isTrue()
    }

    @Test
    fun `US2-AC5 관련 글이 지워지면 post가 null이고 목록에는 남는다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.comment(fan, postId)
        awaitCount(author, 1)
        support.awaitListenersIdle(postId)
        loop.removePost(author, postId).andExpect(status().isNoContent)

        val item = page(author).get("items").single()

        assertThat(item.has("post")).isTrue()
        assertThat(item.get("post").isNull).isTrue()
        assertThat(item.get("postId").asLong()).isEqualTo(postId)
        assertThat(item.get("type").asString()).isEqualTo("POST_COMMENT")
        assertThat(item.get("actor").get("id").asLong()).isEqualTo(fan.id)
    }

    @Test
    fun `90일이 지난 알림은 목록과 안 읽은 수에서 빠진다`() {
        val author = members.onboarded()
        // 시계를 90일 밀면 분석 재시도 폴러가 대기 중인 글을 기본값으로 끝내 몬스터 알림을 만들 수 있다. 글 없이 알림만 둔다
        val postId = unusedId()
        val created = clock.instant()
        val old = support.backdate(support.seed(author.id, postId, seq = 1), created)
        val recent = support.backdate(support.seed(author.id, postId, seq = 2), created.plus(Duration.ofDays(1)))
        val untilExpiry = RETENTION.minusSeconds(1)
        try {
            // 만든 지 89일 23:59:59에는 아직 보인다
            clock.advance(untilExpiry)
            assertThat(queries.page(author.id, null, PAGE_SIZE).items.map { it.notification.id })
                .containsExactly(recent, old)
            assertThat(queries.unreadCount(author.id).count).isEqualTo(2)

            // 정확히 90일이 된 순간부터 빠진다. 정리 작업이 아직 지우지 않았어도 그렇다
            clock.advance(Duration.ofSeconds(1))
            assertThat(queries.page(author.id, null, PAGE_SIZE).items.map { it.notification.id })
                .containsExactly(recent)
            assertThat(queries.unreadCount(author.id).count).isEqualTo(1)
            assertThat(support.notificationsOf(author.id)).hasSize(2)
        } finally {
            clock.advance(RETENTION.negated())
        }
    }

    @Test
    fun `쿼리 수가 쪽 크기와 상관없이 3개다`() {
        val fan = members.onboarded()
        val lone = members.onboarded()
        val busy = members.onboarded()
        val lonePost = loop.postWithoutMonster(lone)
        val busyPosts = (1..3).map { loop.postWithoutMonster(busy) }
        support.seed(lone.id, lonePost, seq = 1, actorId = fan.id)
        (1L..PAGE_SIZE + 5L).forEach { seq ->
            val actor = if (seq % 2 == 0L) fan.id else lone.id
            support.seed(busy.id, busyPosts[(seq % busyPosts.size).toInt()], seq, actorId = actor)
        }

        val (small, smallQueries) = QueryCounter.count { queries.page(lone.id, null, PAGE_SIZE) }
        val (large, largeQueries) = QueryCounter.count { queries.page(busy.id, null, PAGE_SIZE) }

        assertThat(small.items).hasSize(1)
        assertThat(large.items).hasSize(PAGE_SIZE)
        assertThat(large.items).allSatisfy { assertThat(it.post).isNotNull() }
        assertThat(large.items).allSatisfy { assertThat(it.actor).isNotNull() }
        // 알림, 글 미리보기, 행동한 회원을 한 번씩 읽는다
        assertThat(smallQueries).isEqualTo(3)
        assertThat(largeQueries).isEqualTo(3)
    }

    @Test
    fun `커서가 올바르지 않거나 size가 1~50 밖이면 400 INVALID_REQUEST`() {
        val member = members.onboarded()

        listOf(
            get(PATH).param("cursor", "not-a-cursor!"),
            get(PATH).param("cursor", "LTE"),
            get(PATH).param("size", "0"),
            get(PATH).param("size", "51"),
            get(PATH).param("size", "many"),
        ).forEach { request ->
            mockMvc
                .perform(request.bearer(member.accessToken))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `인증 없이 부르면 401`() {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `온보딩 전 회원은 403 ONBOARDING_REQUIRED`() {
        val member = members.signedUp()

        list(member)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
    }

    private fun list(
        member: TestMember,
        cursor: String? = null,
        size: Int? = null,
    ): ResultActions {
        val request = get(PATH).bearer(member.accessToken)
        if (cursor != null) request.param("cursor", cursor)
        if (size != null) request.param("size", size.toString())
        return mockMvc.perform(request)
    }

    private fun page(
        member: TestMember,
        cursor: String? = null,
        size: Int? = null,
    ): JsonNode =
        loop.data(
            list(member, cursor, size)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true)),
        )

    private fun ids(page: JsonNode): List<Long> = page.get("items").toList().map { it.get("notificationId").asLong() }

    private fun seqs(page: JsonNode): List<Long> = page.get("items").toList().map { it.get("seq").asLong() }

    private fun awaitCount(
        member: TestMember,
        count: Long,
    ) {
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).pollInterval(NotificationTestSupport.POLL).until {
            support.notificationsOf(member.id).size.toLong() == count
        }
    }

    private fun nickname(member: TestMember): String =
        jdbcTemplate.queryForObject("select nickname from member where id = ?", String::class.java, member.id)!!

    /** 어떤 회원이나 글도 가리키지 않는 ID. 알림 모듈은 회원과 글 표를 직접 보지 않아 이런 값도 저장된다. */
    private fun unusedId(): Long = UNUSED_ID_BASE + unusedIds.incrementAndGet()

    @TestConfiguration(proxyBeanMethods = false)
    class ClockOverride {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS))
    }

    private companion object {
        const val PATH = "/api/v1/notifications"
        const val PAGE_SIZE = 20
        const val PREVIEW_LENGTH = 50
        const val SEEDED = 21L
        const val TOTAL = 45L
        const val OLD_GROUP_SEQ = 10L
        const val NEW_GROUP_SEQ = 40L
        const val UNUSED_ID_BASE = 9_000_000_000L
        const val UNUSED_ID_RANGE = 1_000_000_000L
        val RETENTION: Duration = Duration.ofDays(90)
    }
}
