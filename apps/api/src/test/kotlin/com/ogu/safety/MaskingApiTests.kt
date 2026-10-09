package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode

/** T046: 응답에서 욕설을 가린다(005 US5, research R6). 욕설은 V5 시드의 낱말이다. 문장은 검증용으로 지어낸 것이다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MaskingApiTests {
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
    fun `US5-AC1 피드 미리보기, 글 상세, 댓글, 공감한 글에서 가려지고 US5-AC5 작성자에게는 원문이다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val postId = safety.insertPost(author, POST_TEXT)
        val commentId = coreLoop.comment(viewer, postId, "진짜 시 발 너무하네요")
        val replyId = coreLoop.comment(author, postId, "그 병신 때문에요", parentId = commentId)
        coreLoop.likePost(viewer, postId)

        // 다른 회원에게는 가려진다
        assertThat(feedItem(viewer, "/api/v1/feed", postId).get("contentPreview").asString()).isEqualTo(POST_MASKED)
        assertThat(feedItem(viewer, "/api/v1/members/me/liked-posts", postId).get("contentPreview").asString())
            .isEqualTo(POST_MASKED)
        assertThat(detail(viewer, postId).get("content").asString()).isEqualTo(POST_MASKED)
        val seenByViewer = comments(viewer, postId).single { it.get("commentId").asLong() == commentId }
        // 내 댓글은 원문, 다른 회원의 답글은 가려진다
        assertThat(seenByViewer.get("content").asString()).isEqualTo("진짜 시 발 너무하네요")
        assertThat(
            seenByViewer
                .get("replies")
                .single()
                .get("content")
                .asString(),
        ).isEqualTo("그 ** 때문에요")

        // 작성자에게는 자기 글과 자기 답글이 원문이고, 다른 회원의 댓글은 가려진다
        assertThat(feedItem(author, "/api/v1/feed", postId).get("contentPreview").asString()).isEqualTo(POST_TEXT)
        assertThat(feedItem(author, "/api/v1/members/me/posts", postId).get("contentPreview").asString())
            .isEqualTo(POST_TEXT)
        assertThat(detail(author, postId).get("content").asString()).isEqualTo(POST_TEXT)
        val seenByAuthor = comments(author, postId).single { it.get("commentId").asLong() == commentId }
        assertThat(seenByAuthor.get("content").asString()).isEqualTo("진짜 *** 너무하네요")
        assertThat(
            seenByAuthor
                .get("replies")
                .single()
                .get("content")
                .asString(),
        ).isEqualTo("그 병신 때문에요")

        // 저장된 본문은 바뀌지 않는다
        assertThat(contentOf("posts", postId)).isEqualTo(POST_TEXT)
        assertThat(contentOf("comments", commentId)).isEqualTo("진짜 시 발 너무하네요")
        assertThat(contentOf("comments", replyId)).isEqualTo("그 병신 때문에요")
    }

    @Test
    fun `US5-AC1 내 댓글의 글 앞부분과 알림의 글 앞부분에서 가려진다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val replier = members.onboarded()
        val postId = safety.insertPost(author, POST_TEXT)
        val commentId = coreLoop.comment(commenter, postId, "힘내세요")
        coreLoop.comment(replier, postId, "저도 응원해요", parentId = commentId)

        val myComment =
            safety
                .data(commenter, "/api/v1/members/me/comments")
                .get("items")
                .values()
                .single { it.get("commentId").asLong() == commentId }
        assertThat(myComment.get("postContentPreview").asString()).isEqualTo(POST_MASKED)
        assertThat(myComment.get("content").asString()).isEqualTo("힘내세요")

        // 원 댓글 주인은 남의 글에 달린 답글 알림을 받는다. 글 앞부분은 가려진다
        safety.awaitNotifications(commenter, "COMMENT_REPLY", 1)
        assertThat(notificationPreview(commenter, "COMMENT_REPLY")).isEqualTo(POST_MASKED)
        // 글쓴이가 받는 알림의 글 앞부분은 자기 글이라 원문이다
        safety.awaitNotifications(author, "POST_COMMENT", 1)
        assertThat(notificationPreview(author, "POST_COMMENT")).isEqualTo(POST_TEXT)
    }

    @Test
    fun `미리보기는 가린 뒤에 잘라 경계에 걸친 욕설이 남지 않는다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val head = "가나다라마바사아자차".repeat(5).dropLast(1)
        val postId = safety.insertPost(author, "${head}시발 뒤에도 글이 더 이어집니다")
        val commentId = coreLoop.comment(viewer, postId, "댓글")

        val preview = feedItem(viewer, "/api/v1/feed", postId).get("contentPreview").asString()
        assertThat(preview).isEqualTo("$head*...")
        val myComment =
            safety
                .data(viewer, "/api/v1/members/me/comments")
                .get("items")
                .values()
                .single { it.get("commentId").asLong() == commentId }
        assertThat(myComment.get("postContentPreview").asString()).isEqualTo("$head*")
    }

    @Test
    fun `US5-AC4 감정 분석과 위험 감지는 원문으로 한다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        // 욕설 사이에 위기 표현이 걸쳐 있다. 가린 글로 판정하면 놓친다
        val crisis = coreLoop.createPost(author, "시발 ${SafetyFixture.CRISIS_TEXT}")
        val analyzed = coreLoop.createPost(members.onboarded(), "[불안:낮음] 병신 같은 하루였어요")

        assertThat(safety.postState(crisis)).containsEntry("hidden", true).containsEntry("risk_level", "CRISIS")
        // 가짜 분석기는 머리말을 원문에서 읽는다. 몬스터가 생기면 분석이 원문을 받은 것이다
        coreLoop.awaitMonster(analyzed)
        assertThat(detail(viewer, analyzed).get("content").asString()).isEqualTo("[불안:낮음] ** 같은 하루였어요")
        assertThat(contentOf("posts", analyzed)).isEqualTo("[불안:낮음] 병신 같은 하루였어요")
    }

    @Test
    fun `US5-AC6 낱말을 더하면 예전 글도 가려지고 빼면 다시 보인다`() {
        val operator = members.onboarded().also(safety::grantOperator)
        val viewer = members.onboarded()
        val postId = safety.insertPost(members.onboarded(), "어제 쓴 글에 오구검증욕설 이 들어 있다")
        assertThat(detail(viewer, postId).get("content").asString()).contains("오구검증욕설")

        val added =
            coreLoop.data(
                safety
                    .send(operator, HttpMethod.POST, TERMS, mapOf("kind" to "PROFANITY", "term" to "오구검증욕설"))
                    .andExpect(status().isOk),
            )
        try {
            assertThat(detail(viewer, postId).get("content").asString()).isEqualTo("어제 쓴 글에 ****** 이 들어 있다")
            assertThat(feedItem(viewer, "/api/v1/feed", postId).get("contentPreview").asString())
                .isEqualTo("어제 쓴 글에 ****** 이 들어 있다")
        } finally {
            safety
                .send(operator, HttpMethod.DELETE, "$TERMS/${added.get("termId").asLong()}")
                .andExpect(status().isNoContent)
        }

        assertThat(detail(viewer, postId).get("content").asString()).isEqualTo("어제 쓴 글에 오구검증욕설 이 들어 있다")
        assertThat(contentOf("posts", postId)).isEqualTo("어제 쓴 글에 오구검증욕설 이 들어 있다")
    }

    private fun feedItem(
        viewer: TestMember,
        path: String,
        postId: Long,
    ): JsonNode = safety.allPages(viewer, path, "size" to "50").first { it.get("postId").asLong() == postId }

    private fun detail(
        viewer: TestMember,
        postId: Long,
    ): JsonNode = safety.data(viewer, "/api/v1/posts/$postId")

    private fun comments(
        viewer: TestMember,
        postId: Long,
    ): List<JsonNode> =
        safety
            .data(viewer, "/api/v1/posts/$postId/comments")
            .get("items")
            .values()
            .toList()

    private fun notificationPreview(
        receiver: TestMember,
        type: String,
    ): String =
        safety
            .data(receiver, "/api/v1/notifications")
            .get("items")
            .values()
            .single { it.get("type").asString() == type }
            .get("post")
            .get("contentPreview")
            .asString()

    private fun contentOf(
        table: String,
        id: Long,
    ): String = jdbcTemplate.queryForObject("select content from $table where id = ?", String::class.java, id)!!

    private companion object {
        const val POST_TEXT = "오늘 팀장이 병신 같은 소리를 해서 화가 났다"
        const val POST_MASKED = "오늘 팀장이 ** 같은 소리를 해서 화가 났다"
        const val TERMS = "/api/v1/operator/terms"
    }
}
