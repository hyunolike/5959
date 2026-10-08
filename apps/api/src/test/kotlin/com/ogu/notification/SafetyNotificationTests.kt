package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.SafetyFixture.Companion.CONCERN_TEXT
import com.ogu.support.SafetyFixture.Companion.CRISIS_TEXT
import com.ogu.support.SafetyFixture.Companion.SAFE_TEXT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/** T016: 위험 판정이 작성자에게 가는 도움 안내 알림을 만든다(005 US1-AC6, AC7, research R7, R8). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SafetyNotificationTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture
    private lateinit var safety: SafetyFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
    }

    @Test
    fun `US1-AC6 위기와 우려 판정이 SUPPORT_NOTICE 알림을 만들고 응답에 본문과 단계가 없다`() {
        val author = members.onboarded()

        val crisis = coreLoop.createPost(author, CRISIS_TEXT)
        val concern = coreLoop.createPost(author, CONCERN_TEXT)
        coreLoop.createPost(author, SAFE_TEXT)

        safety.awaitNotifications(author, "SUPPORT_NOTICE", 2)
        assertThat(safety.notificationKeys(author, "SUPPORT_NOTICE"))
            .containsExactlyInAnyOrder("RISK:POST:$crisis:CRISIS", "RISK:POST:$concern:CONCERN")
        val items =
            safety
                .data(author, "/api/v1/notifications")
                .get("items")
                .values()
                .filter { it.get("type").asString() == "SUPPORT_NOTICE" }
        assertThat(items).hasSize(2)
        items.forEach { item ->
            assertThat(item.get("actor").isNull).isTrue()
            assertThat(item.get("actorCount").asInt()).isEqualTo(1)
            assertThat(item.get("read").asBoolean()).isFalse()
            // 단계는 응답에 없다. 글 앞부분은 작성자 자신의 글이라 그대로 보인다(숨긴 글이어도)
            assertThat(item.has("level")).isFalse()
            assertThat(item.get("post").get("postId").asLong()).isIn(crisis, concern)
        }
    }

    @Test
    fun `같은 대상에 같은 단계는 한 번만 알리고 단계가 올라가면 한 번 더 알린다`() {
        val author = members.onboarded()
        val postId = coreLoop.createPost(author, CONCERN_TEXT)
        safety.awaitNotifications(author, "SUPPORT_NOTICE", 1)

        // 같은 단계로 다시 판정돼도 새 알림은 없다
        coreLoop.updatePost(author, postId, mapOf("content" to "$CONCERN_TEXT 정말로요")).andExpect(status().isNoContent)
        safety.awaitListenersIdle()
        assertThat(safety.notificationKeys(author, "SUPPORT_NOTICE")).hasSize(1)

        coreLoop.updatePost(author, postId, mapOf("content" to CRISIS_TEXT)).andExpect(status().isNoContent)
        safety.awaitNotifications(author, "SUPPORT_NOTICE", 2)

        // 내려갔다가 같은 단계로 다시 올라와도 이미 알린 단계는 다시 알리지 않는다
        coreLoop.updatePost(author, postId, mapOf("content" to SAFE_TEXT)).andExpect(status().isNoContent)
        coreLoop.updatePost(author, postId, mapOf("content" to CRISIS_TEXT)).andExpect(status().isNoContent)
        safety.awaitListenersIdle()
        assertThat(safety.notificationKeys(author, "SUPPORT_NOTICE"))
            .containsExactly("RISK:POST:$postId:CONCERN", "RISK:POST:$postId:CRISIS")
    }

    @Test
    fun `위기 표현이 든 댓글의 작성자에게 댓글 ID가 담긴 도움 안내가 간다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = coreLoop.createPost(author, SAFE_TEXT)

        val commentId = coreLoop.comment(commenter, postId, CRISIS_TEXT)

        safety.awaitNotifications(commenter, "SUPPORT_NOTICE", 1)
        val keys = safety.notificationKeys(commenter, "SUPPORT_NOTICE")
        assertThat(keys).containsExactly("RISK:COMMENT:$commentId:CRISIS")
        val row =
            jdbcTemplate.queryForMap(
                "select post_id, comment_id from notification where receiver_id = ? and type = 'SUPPORT_NOTICE'",
                commenter.id,
            )
        assertThat(row["post_id"]).isEqualTo(postId)
        assertThat(row["comment_id"]).isEqualTo(commentId)
    }

    @Test
    fun `US1-AC7 숨긴 댓글과 숨긴 글에는 댓글, 공감, 몬스터 알림이 새로 생기지 않는다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        // 위기 글은 저장과 함께 숨겨진다. 감정 분석이 끝나 몬스터가 생겨도 생성 알림은 없다
        val hidden = coreLoop.createPost(author, "[불안:낮음] $CRISIS_TEXT")
        coreLoop.awaitMonster(hidden)
        // 위기 댓글은 글쓴이에게 댓글 알림을 만들지 않는다. 보이는 글은 분석이 실패하게 해 몬스터 알림이 섞이지 않게 한다
        val visible = coreLoop.postWithoutMonster(author)
        coreLoop.comment(commenter, visible, CRISIS_TEXT)
        safety.awaitNotifications(commenter, "SUPPORT_NOTICE", 1)
        safety.awaitListenersIdle()

        val types =
            jdbcTemplate.queryForList(
                "select type from notification where receiver_id = ?",
                String::class.java,
                author.id,
            )
        assertThat(types).containsExactly("SUPPORT_NOTICE")
        assertThat(coreLoop.monster(hidden)).isNotNull()
    }
}
