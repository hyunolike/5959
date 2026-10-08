package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.TestMember
import com.ogu.support.bearer
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
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/** T033: 안 읽은 알림 수 `GET /api/v1/notifications/unread-count`(FR-009, research R11). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class UnreadCountApiTests {
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
    fun `알림이 없으면 안 읽은 수와 마지막 번호가 0이다`() {
        val member = members.onboarded()

        unreadCount(member)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.count").value(0))
            .andExpect(jsonPath("$.data.latestSeq").value(0))
            .andExpect(jsonPath("$.error").doesNotExist())
    }

    @Test
    fun `안 읽은 수는 읽지 않은 알림만 세고 마지막 번호는 이 회원의 마지막 전달 번호다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.comment(fan, postId)
        loop.likePost(fan, postId).andExpect(status().isOk)
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until { support.notificationsOf(author.id).size == 2 }
        support.awaitListenersIdle(postId)
        val rows = support.notificationsOf(author.id)

        unreadCount(author)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(2))
            .andExpect(jsonPath("$.data.latestSeq").value(rows.last().seq))

        support.markRead(rows.first().id)
        unreadCount(author)
            .andExpect(jsonPath("$.data.count").value(1))
            .andExpect(jsonPath("$.data.latestSeq").value(support.lastSeq(author.id)))
        assertThat(support.lastSeq(author.id)).isEqualTo(rows.last().seq)
    }

    @Test
    fun `보관 기간이 지난 안 읽은 알림은 세지 않는다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.comment(fan, postId)
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until { support.notificationsOf(author.id).isNotEmpty() }
        jdbcTemplate.update(
            "update notification set created_at = now() - interval '90 days 1 second' where receiver_id = ?",
            author.id,
        )

        unreadCount(author).andExpect(jsonPath("$.data.count").value(0))
    }

    @Test
    fun `인증 없이 부르면 401`() {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `온보딩 전 회원은 403 ONBOARDING_REQUIRED`() {
        val member = members.signedUp()

        unreadCount(member)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
    }

    private fun unreadCount(member: TestMember) = mockMvc.perform(get(PATH).bearer(member.accessToken))

    private companion object {
        const val PATH = "/api/v1/notifications/unread-count"
    }
}
