package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.post.PostLiked
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T018: 공감 알림 묶음(US1-AC2, FR-002, research R7). 안 읽은 묶음은 글마다 하나이고, 같은 회원의 공감은 묶음이 바뀌어도
 * 한 번만 센다.
 */
@SpringBootTest
@EnableScenarios
@Import(TestcontainersConfiguration::class)
class LikeNotificationTests {
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
    fun `US1-AC2 B와 C가 차례로 공감하면 안 읽은 공감 알림 하나에 actor_count 2, latest_actor는 C이고 seq가 새 번호로 오른다`() {
        val author = members.onboarded()
        val b = members.onboarded()
        val c = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        loop.likePost(b, postId).andExpect(status().isOk)
        val first = awaitLikeGroup(author.id) { it.actorCount == 1 }
        loop.likePost(c, postId).andExpect(status().isOk)
        val second = awaitLikeGroup(author.id) { it.actorCount == 2 }

        support.awaitListenersIdle(postId)
        val rows = support.notificationsOf(author.id)
        assertThat(rows).containsExactly(second)
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.type).isEqualTo("POST_LIKE")
        assertThat(second.postId).isEqualTo(postId)
        assertThat(second.latestActorId).isEqualTo(c.id)
        assertThat(second.dedupKey).isNull()
        assertThat(second.read).isFalse()
        assertThat(second.seq).isGreaterThan(first.seq)
        assertThat(second.seq).isEqualTo(support.lastSeq(author.id))
        assertThat(support.participants(postId)).containsExactlyInAnyOrder(b.id to first.id, c.id to first.id)
    }

    @Test
    fun `US1-AC2 공감 취소 후 다시 공감해도 알림과 숫자가 그대로다`() {
        val author = members.onboarded()
        val b = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.likePost(b, postId).andExpect(status().isOk)
        val group = awaitLikeGroup(author.id) { it.actorCount == 1 }
        support.awaitListenersIdle(postId)

        loop.unlikePost(b, postId).andExpect(status().isOk)
        loop.likePost(b, postId).andExpect(status().isOk)
        loop.unlikePost(b, postId).andExpect(status().isOk)
        loop.likePost(b, postId).andExpect(status().isOk)

        support.awaitListenersIdle(postId)
        assertThat(support.notificationsOf(author.id)).containsExactly(group)
        assertThat(support.lastSeq(author.id)).isEqualTo(group.seq)
        assertThat(support.participants(postId)).containsExactly(b.id to group.id)
    }

    @Test
    fun `US1-AC2 묶음을 읽은 뒤 온 공감은 새 묶음을 만들고 이미 센 회원은 다시 세지 않는다`() {
        val author = members.onboarded()
        val b = members.onboarded()
        val c = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.likePost(b, postId).andExpect(status().isOk)
        val readGroup = awaitLikeGroup(author.id) { it.actorCount == 1 }
        support.markRead(readGroup.id)

        loop.likePost(c, postId).andExpect(status().isOk)
        val newGroup = awaitLikeGroup(author.id) { it.id != readGroup.id }
        loop.unlikePost(b, postId).andExpect(status().isOk)
        loop.likePost(b, postId).andExpect(status().isOk)

        support.awaitListenersIdle(postId)
        val rows = support.notificationsOf(author.id)
        assertThat(rows.map { Triple(it.id, it.actorCount, it.read) })
            .containsExactly(Triple(readGroup.id, 1, true), Triple(newGroup.id, 1, false))
        assertThat(rows.last().latestActorId).isEqualTo(c.id)
        assertThat(rows.last().seq).isGreaterThan(readGroup.seq)
        assertThat(support.participants(postId))
            .containsExactlyInAnyOrder(b.id to readGroup.id, c.id to newGroup.id)
    }

    @Test
    fun `댓글 공감은 알림을 만들지 않음`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(commenter, postId)

        loop.likeComment(fan, commentId).andExpect(status().isOk)
        loop.likeComment(author, commentId).andExpect(status().isOk)

        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until { support.notificationsOf(author.id).isNotEmpty() }
        support.awaitListenersIdle(postId)
        assertThat(support.notificationsOf(author.id).map { it.type }).containsExactly("POST_COMMENT")
        assertThat(support.notificationsOf(commenter.id)).isEmpty()
    }

    @Test
    fun `지운 글 공감은 만들지 않음`(scenario: Scenario) {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.deletePost(postId)

        scenario
            .publish(PostLiked(postId, fan.id))
            .andWaitForStateChange { support.incompletePublications(postId) == 0 }

        support.awaitListenersIdle(postId)
        assertThat(support.notificationsOf(author.id)).isEmpty()
        assertThat(support.lastSeq(author.id)).isZero()
        assertThat(support.participants(postId)).isEmpty()
    }

    private fun awaitLikeGroup(
        receiverId: Long,
        condition: (NotificationRow) -> Boolean,
    ): NotificationRow {
        await()
            .atMost(NotificationTestSupport.AWAIT_LIMIT)
            .pollInterval(NotificationTestSupport.POLL)
            .until { support.notificationsOf(receiverId, "POST_LIKE").any(condition) }
        return support.notificationsOf(receiverId, "POST_LIKE").last(condition)
    }
}
