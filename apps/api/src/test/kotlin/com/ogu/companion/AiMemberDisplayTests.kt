package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.notification.NotificationTestSupport
import com.ogu.post.CompanionReactionOutcome
import com.ogu.post.PostCompanionApi
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * AI 친구가 응답에서 AI로 드러나는지(009 US2), AI 회원에게 알림이 쌓이지 않는지(FR-017). AI 친구의 댓글과 공감은
 * 주기 작업 없이 `post`의 파사드로 바로 남긴다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AiMemberDisplayTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var companionApi: PostCompanionApi

    @Autowired
    lateinit var redis: StringRedisTemplate

    @Autowired
    lateinit var container: RedisMessageListenerContainer

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var support: NotificationTestSupport
    private lateinit var companions: List<Long>

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        support = NotificationTestSupport(jdbcTemplate, redis, container)
        companions = jdbcTemplate.queryForList("select member_id from companion order by id", Long::class.java)
    }

    @Test
    fun `US2-AC1 AI 친구의 댓글은 작성자에 ai가 true이고 사람의 댓글은 false다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        react(postId, companions[0])
        loop.comment(members.onboarded(), postId, "저도 응원해요")

        loop
            .comments(author, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].author.id").value(companions[0]))
            .andExpect(jsonPath("$.data.items[0].author.nickname").value("다정한 오구"))
            .andExpect(jsonPath("$.data.items[0].author.ai").value(true))
            .andExpect(jsonPath("$.data.items[1].author.ai").value(false))
    }

    @Test
    fun `US2-AC3 AI 친구의 댓글 작성자는 직군과 경력이 null이다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        react(postId, companions[1])

        val item =
            loop
                .data(loop.comments(author, postId))
                .get("items")
                .get(0)
                .get("author")

        // 계약의 CommentAuthor: 두 필드는 빠지지 않고 null로 온다
        assertThat(item.has("jobRole")).isTrue()
        assertThat(item.get("jobRole").isNull).isTrue()
        assertThat(item.has("careerYear")).isTrue()
        assertThat(item.get("careerYear").isNull).isTrue()
        assertThat(item.get("nickname").asString()).isEqualTo("씩씩한 오구")
    }

    @Test
    fun `US2-AC2 AI 친구가 한 일의 알림은 actor의 ai가 true이고 사람이 한 일은 false다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val other = members.onboarded()
        val otherPost = loop.postWithoutMonster(author)
        react(postId, companions[2])
        loop.comment(other, otherPost)
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
            support.notificationsOf(author.id, "POST_COMMENT", "POST_LIKE").size == 3
        }

        val items = notifications(author).get("items").toList()

        val byAi = items.filter { it.get("postId").asLong() == postId }
        assertThat(byAi.map { it.get("type").asString() }).containsExactlyInAnyOrder("POST_COMMENT", "POST_LIKE")
        assertThat(byAi).allSatisfy { item ->
            assertThat(item.get("actor").get("ai").asBoolean()).isTrue()
            assertThat(item.get("actor").get("nickname").asString()).isEqualTo("차분한 오구")
        }
        val byHuman = items.single { it.get("postId").asLong() == otherPost }
        assertThat(byHuman.get("actor").get("ai").asBoolean()).isFalse()
    }

    @Test
    fun `FR-017 AI 친구의 댓글에 답글이 달려도 AI 회원에게 알림이 없다`() {
        val author = members.onboarded()
        val replier = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = react(postId, companions[0])

        val replyId = loop.comment(replier, postId, "저도 그렇게 생각해요", parentId = commentId)

        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
            support.notificationsOf(author.id, "POST_REPLY").any { it.commentId == replyId }
        }
        support.awaitListenersIdle(postId)
        assertThat(aiNotifications()).isZero()
        // 사람에게 가는 알림은 그대로다
        assertThat(support.notificationsOf(author.id, "POST_COMMENT").map { it.commentId }).containsExactly(commentId)
    }

    @Test
    fun `FR-017 AI 친구가 깎은 몬스터가 처치돼도 AI 회원에게 알림이 없고 함께한 사람은 받는다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val another = members.onboarded()
        val postId = loop.postWithMonster(author, intensity = "낮음")
        react(postId, companions[0])
        for (member in listOf(helper, another)) {
            loop.likePost(member, postId).andExpect(status().isOk)
            loop.comment(member, postId)
        }

        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
            support.notificationsOf(helper.id, "MONSTER_DEFEATED_TOGETHER").size == 1
        }
        support.awaitListenersIdle(postId)
        assertThat(loop.monster(postId)!!.hp).isZero()
        assertThat(loop.hpLogs(postId).map { it.memberId }).contains(companions[0])
        assertThat(aiNotifications()).isZero()
        assertThat(support.notificationsOf(author.id, "MONSTER_DEFEATED")).hasSize(1)
    }

    /** AI 친구의 댓글과 공감을 남기고 댓글 ID를 돌려준다. */
    private fun react(
        postId: Long,
        companionId: Long,
    ): Long {
        val result = companionApi.react(postId, companionId, "토닥토닥, 오늘도 애썼어요.", companions)
        assertThat(result.outcome).isEqualTo(CompanionReactionOutcome.REACTED)
        return result.commentId!!
    }

    private fun aiNotifications(): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from notification where receiver_id in (select id from member where kind = 'AI')",
            Int::class.java,
        )!!

    private fun notifications(member: TestMember): JsonNode =
        jsonMapper
            .readTree(
                mockMvc
                    .perform(get("/api/v1/notifications").bearer(member.accessToken))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            ).get("data")
}
