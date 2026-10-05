package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.notification.application.NotificationDraft
import com.ogu.notification.application.NotificationWriter
import com.ogu.notification.domain.NotificationType
import com.ogu.shared.config.EventPublicationResubmitter
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T020: 알림 실패는 원래 행동을 실패시키지 않는다(FR-003, research R6). 알림은 커밋 뒤 비동기로 만들어지므로 리스너가
 * 예외를 던져도 댓글과 공감은 저장되고 몬스터 HP도 줄어든다. 끝나지 않은 발행은 Event Publication Registry에 남고,
 * 다시 보내면 알림이 하나 생긴다. 정기 재전송은 끄고 [EventPublicationResubmitter.resubmit]을 직접 부른다.
 */
@SpringBootTest(
    properties = [
        "ogu.emotion.retry.scheduler-enabled=false",
        "ogu.events.resubmit.enabled=false",
        "ogu.events.resubmit.older-than=0s",
    ],
)
@Import(TestcontainersConfiguration::class)
class NotificationFailureIsolationTests {
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
    lateinit var resubmitter: EventPublicationResubmitter

    @MockitoSpyBean
    lateinit var writer: NotificationWriter

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
    fun `알림 리스너가 예외를 던져도 댓글, 공감 API는 성공하고 몬스터 HP도 반영된다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithMonster(author)
        doThrow(IllegalStateException("알림 장애")).`when`(writer).writeAll(anyList() ?: emptyList())
        doThrow(IllegalStateException("알림 장애")).`when`(writer).addLike(anyLong(), anyLong(), anyLong())

        loop.likePost(fan, postId).andExpect(status().isOk)
        loop.comment(fan, postId)

        assertThat(loop.monster(postId)!!.hp).isEqualTo(10 - 1 - 3)
        verify(writer, timeout(AWAIT_MILLIS)).addLike(author.id, postId, fan.id)
        verify(writer, timeout(AWAIT_MILLIS)).writeAll(commentDrafts())
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until { support.incompletePublications(postId) >= 2 }
        assertThat(support.notificationsOf(author.id, "POST_LIKE", "POST_COMMENT")).isEmpty()
        assertThat(jdbcTemplate.queryForObject(LIKE_COUNT, Int::class.java, postId, fan.id)).isEqualTo(1)
    }

    @Test
    fun `미완료 이벤트 발행이 남고 EventPublicationResubmitter로 다시 보내면 알림이 하나 생긴다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        doThrow(IllegalStateException("일시 장애"))
            .doCallRealMethod()
            .`when`(writer)
            .writeAll(anyList() ?: emptyList())

        val commentId = loop.comment(commenter, postId)

        verify(writer, timeout(AWAIT_MILLIS)).writeAll(anyList() ?: emptyList())
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
            support.incompletePublications("commentId", commentId) == 1
        }
        assertThat(support.notificationsOf(author.id)).isEmpty()

        await().atMost(NotificationTestSupport.AWAIT_LIMIT).pollInterval(NotificationTestSupport.POLL).untilAsserted {
            resubmitter.resubmit()
            assertThat(support.notificationsOf(author.id)).isNotEmpty()
        }
        support.awaitListenersIdle(postId)
        resubmitter.resubmit()

        val row = support.notificationsOf(author.id).single()
        assertThat(row.type).isEqualTo("POST_COMMENT")
        assertThat(row.commentId).isEqualTo(commentId)
        assertThat(support.incompletePublications("commentId", commentId)).isZero()
    }

    /** 댓글 알림을 쓰려던 호출(몬스터 생성 알림 호출과 구분한다). */
    private fun commentDrafts(): List<NotificationDraft> =
        argThat<List<NotificationDraft>> { drafts -> drafts.any { it.type == NotificationType.POST_COMMENT } }
            ?: emptyList()

    private companion object {
        const val AWAIT_MILLIS = 10_000L
        const val LIKE_COUNT = "select count(*) from post_likes where post_id = ? and member_id = ?"
    }
}
