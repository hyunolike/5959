package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.post.CommentCreated
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration

/**
 * T017: 댓글과 답글 알림(US1-AC1, US1-AC5, FR-001, research R6). 댓글은 실제 API로 달고, 알림은 커밋 뒤 비동기로
 * 생기므로 기다려 확인한다. 이벤트를 직접 다시 보내는 경우는 `Scenario`로 발행한다.
 */
@SpringBootTest
@EnableScenarios
@Import(TestcontainersConfiguration::class)
class CommentNotificationTests {
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

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var support: NotificationTestSupport

    @BeforeEach
    fun setUp() {
        val mockMvc =
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        support = NotificationTestSupport(jdbcTemplate, redis, container)
    }

    @Test
    fun `US1-AC1 다른 회원이 댓글을 달면 글쓴이에게 POST_COMMENT 하나`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        val commentId = loop.comment(commenter, postId)

        val rows = awaitCommentNotifications(author.id, 1)
        support.awaitListenersIdle(postId)
        assertThat(support.notificationsOf(author.id, *COMMENT_TYPES)).isEqualTo(rows)
        val row = rows.single()
        assertThat(row.type).isEqualTo("POST_COMMENT")
        assertThat(row.postId).isEqualTo(postId)
        assertThat(row.commentId).isEqualTo(commentId)
        assertThat(row.latestActorId).isEqualTo(commenter.id)
        assertThat(row.actorCount).isEqualTo(1)
        assertThat(row.dedupKey).isEqualTo("COMMENT:$commentId")
        assertThat(row.read).isFalse()
        assertThat(support.notificationsOf(commenter.id)).isEmpty()
    }

    @Test
    fun `US1-AC1 다른 회원의 답글은 글쓴이에게 POST_REPLY, 원 댓글 주인에게 COMMENT_REPLY를 하나씩 만든다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val replier = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(commenter, postId)

        val replyId = loop.comment(replier, postId, parentId = commentId)

        awaitCommentNotifications(author.id, 2)
        awaitCommentNotifications(commenter.id, 1)
        support.awaitListenersIdle(postId)
        val toAuthor = support.notificationsOf(author.id, *COMMENT_TYPES)
        assertThat(toAuthor.map { it.type to it.commentId })
            .containsExactly("POST_COMMENT" to commentId, "POST_REPLY" to replyId)
        val toCommenter = support.notificationsOf(commenter.id, *COMMENT_TYPES).single()
        assertThat(toCommenter.type).isEqualTo("COMMENT_REPLY")
        assertThat(toCommenter.commentId).isEqualTo(replyId)
        assertThat(toCommenter.latestActorId).isEqualTo(replier.id)
        assertThat(toCommenter.dedupKey).isEqualTo("COMMENT:$replyId")
        assertThat(support.notificationsOf(replier.id)).isEmpty()
    }

    @Test
    fun `US1-AC5 글쓴이가 자기 글에 댓글이나 답글을 달면 글쓴이에게 알림이 없다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val own = loop.comment(author, postId)
        loop.comment(author, postId, parentId = own)

        val othersComment = loop.comment(commenter, postId)
        awaitCommentNotifications(author.id, 1)
        loop.comment(author, postId, parentId = othersComment)

        awaitCommentNotifications(commenter.id, 1)
        support.awaitListenersIdle(postId)
        assertThat(support.notificationsOf(author.id, *COMMENT_TYPES).map { it.commentId })
            .containsExactly(othersComment)
        // 글쓴이의 답글은 원 댓글 주인에게만 간다
        assertThat(support.notificationsOf(commenter.id, *COMMENT_TYPES).single().type).isEqualTo("COMMENT_REPLY")
    }

    @Test
    fun `원 댓글 주인이 글쓴이면 한 답글로 그 회원에게 알림이 하나뿐`() {
        val author = members.onboarded()
        val replier = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val authorsComment = loop.comment(author, postId)

        val replyId = loop.comment(replier, postId, parentId = authorsComment)

        awaitCommentNotifications(author.id, 1)
        support.awaitListenersIdle(postId)
        val row = support.notificationsOf(author.id, *COMMENT_TYPES).single()
        assertThat(row.type).isEqualTo("POST_REPLY")
        assertThat(row.commentId).isEqualTo(replyId)
    }

    @Test
    fun `답글 작성자가 원 댓글 주인이면 댓글 주인 알림 없음`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(commenter, postId)

        val replyId = loop.comment(commenter, postId, parentId = commentId)

        awaitCommentNotifications(author.id, 2)
        support.awaitListenersIdle(postId)
        assertThat(support.notificationsOf(author.id, *COMMENT_TYPES).map { it.type to it.commentId })
            .containsExactly("POST_COMMENT" to commentId, "POST_REPLY" to replyId)
        assertThat(support.notificationsOf(commenter.id)).isEmpty()
    }

    @Test
    fun `한 사람이 댓글을 여러 개 달면 댓글마다 알림`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        val commentIds = (1..3).map { loop.comment(commenter, postId, "댓글 $it") }

        awaitCommentNotifications(author.id, 3)
        support.awaitListenersIdle(postId)
        val rows = support.notificationsOf(author.id, *COMMENT_TYPES)
        assertThat(rows.map { it.commentId }).containsExactlyInAnyOrderElementsOf(commentIds)
        assertThat(rows.map { it.type }).containsOnly("POST_COMMENT")
        assertThat(rows.map { it.seq }).doesNotHaveDuplicates()
    }

    @Test
    fun `지운 글이나 지운 댓글이면 만들지 않음`(scenario: Scenario) {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val livePost = loop.postWithoutMonster(author)
        val deletedComment = insertComment(livePost, commenter.id, deleted = true)
        val deletedPost = loop.postWithoutMonster(author)
        val commentOnDeletedPost = insertComment(deletedPost, commenter.id, deleted = false)
        loop.deletePost(deletedPost)

        scenario
            .publish(CommentCreated(livePost, deletedComment, commenter.id))
            .andWaitForStateChange { support.incompletePublications(livePost) == 0 }
        scenario
            .publish(CommentCreated(deletedPost, commentOnDeletedPost, commenter.id))
            .andWaitForStateChange { support.incompletePublications(deletedPost) == 0 }

        support.awaitListenersIdle(livePost)
        support.awaitListenersIdle(deletedPost)
        assertThat(support.notificationsOf(author.id)).isEmpty()
        assertThat(support.lastSeq(author.id)).isZero()
    }

    @Test
    fun `같은 CommentCreated를 두 번 보내도 알림 하나이고 신호도 한 번`(scenario: Scenario) {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        support.subscribeSignals()
        try {
            val commentId = loop.comment(commenter, postId)
            val first = awaitCommentNotifications(author.id, 1).single()
            await().atMost(NotificationTestSupport.AWAIT_LIMIT).until { support.newSignalsFor(author.id).isNotEmpty() }

            scenario
                .publish(CommentCreated(postId, commentId, commenter.id))
                .andWaitForStateChange { support.incompletePublications(postId) == 0 }

            support.awaitListenersIdle(postId)
            assertThat(support.notificationsOf(author.id)).containsExactly(first)
            assertThat(support.lastSeq(author.id)).isEqualTo(first.seq)
            await()
                .during(Duration.ofMillis(800))
                .atMost(Duration.ofSeconds(3))
                .until { support.newSignalsFor(author.id) == listOf("${author.id}:n:${first.seq}") }
        } finally {
            support.unsubscribeSignals()
        }
    }

    private fun awaitCommentNotifications(
        receiverId: Long,
        count: Int,
    ): List<NotificationRow> {
        await()
            .atMost(NotificationTestSupport.AWAIT_LIMIT)
            .pollInterval(NotificationTestSupport.POLL)
            .until { support.notificationsOf(receiverId, *COMMENT_TYPES).size >= count }
        return support.notificationsOf(receiverId, *COMMENT_TYPES)
    }

    /** 이벤트 없이 댓글 행만 만든다. 리스너가 늦게 돌아 그사이 지워진 댓글을 흉내 낸다. */
    private fun insertComment(
        postId: Long,
        authorId: Long,
        deleted: Boolean,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into comments (post_id, author_id, content, like_count, deleted_at, created_at, updated_at)
            values (?, ?, '나중에 지운 댓글', 0, case when ? then now() end, now(), now())
            returning id
            """.trimIndent(),
            Long::class.java,
            postId,
            authorId,
            deleted,
        )!!

    private companion object {
        val COMMENT_TYPES = arrayOf("POST_COMMENT", "POST_REPLY", "COMMENT_REPLY")
    }
}
