package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.post.ContentType
import com.ogu.post.PostModerationApi
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/** T038: 숨겨진 내 글과 댓글의 재검토 요청과 운영자의 결정(005 US4-AC8, AC9, research R11). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ReviewRequestApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var moderation: PostModerationApi

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture
    private lateinit var safety: SafetyFixture
    private lateinit var operator: TestMember

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        operator = members.onboarded().also(safety::grantOperator)
    }

    @Test
    fun `US4-AC8 숨겨진 내 글에 재검토를 요청하면 204이고 운영자 조회에 나타나며 두 번째는 409다`() {
        val author = members.onboarded()
        val postId = hiddenPost(author, "가려졌지만 문제없다고 생각하는 글")
        coreLoop.detail(author, postId).andExpect(jsonPath("$.data.safety.reviewRequested").value(false))

        request(author, "POST", postId).andExpect(status().isNoContent)

        coreLoop
            .detail(author, postId)
            .andExpect(jsonPath("$.data.safety.reviewRequested").value(true))
            .andExpect(jsonPath("$.data.safety.hidden").value(true))
        val review = pendingReviews().single { it.get("target").get("targetId").asLong() == postId }
        assertThat(review.get("status").asString()).isEqualTo("PENDING")
        assertThat(review.get("target").get("targetType").asString()).isEqualTo("POST")
        assertThat(review.get("target").get("authorId").asLong()).isEqualTo(author.id)
        assertThat(review.get("target").get("content").asString()).isEqualTo("가려졌지만 문제없다고 생각하는 글")
        assertThat(review.get("target").get("hidden").asBoolean()).isTrue()

        request(author, "POST", postId)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("REVIEW_ALREADY_REQUESTED"))
        assertThat(reviewRows(postId)).hasSize(1)
    }

    @Test
    fun `US4-AC8 숨겨진 내 댓글도 요청할 수 있고 댓글 응답의 reviewRequested가 true가 된다`() {
        val author = members.onboarded()
        val postId = safety.insertPost(members.onboarded(), "댓글이 달린 글")
        val commentId = coreLoop.comment(author, postId, "가려진 내 댓글")
        moderation.hide(ContentType.COMMENT, commentId, "RISK")

        request(author, "COMMENT", commentId).andExpect(status().isNoContent)

        coreLoop
            .comments(author, postId)
            .andExpect(jsonPath("$.data.items[0].safety.reviewRequested").value(true))
        val row =
            jdbcTemplate.queryForMap(
                "select * from review_request where target_type = 'COMMENT' and target_id = ?",
                commentId,
            )
        assertThat(row).containsEntry("target_type", "COMMENT").containsEntry("post_id", postId)
    }

    @Test
    fun `남의 것, 숨겨지지 않은 것, 없는 것은 404이고 빠진 입력은 400이다`() {
        val author = members.onboarded()
        val other = members.onboarded()
        val hidden = hiddenPost(author, "가려진 글")
        val visible = safety.insertPost(author, "보이는 글")
        val visibleComment = coreLoop.comment(author, visible, "보이는 댓글")

        request(other, "POST", hidden)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        request(author, "POST", visible).andExpect(status().isNotFound)
        request(author, "POST", Long.MAX_VALUE).andExpect(status().isNotFound)
        request(author, "COMMENT", visibleComment)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        listOf("{}", """{"targetType":"POST"}""", """{"targetId":1}""", """{"targetType":"MEMBER","targetId":1}""")
            .forEach { body ->
                safety.send(author, HttpMethod.POST, PATH, body).andExpect(status().isBadRequest)
            }

        assertThat(reviewRows(hidden)).isEmpty()
        coreLoop.detail(author, hidden).andExpect(jsonPath("$.data.safety.reviewRequested").value(false))
    }

    @Test
    fun `US4-AC9 유지로 닫으면 REVIEW_KEPT 알림이 가고 글은 숨긴 채다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val postId = hiddenPost(author, "다시 봐도 가려 둘 글")
        request(author, "POST", postId).andExpect(status().isNoContent)
        val reviewId = reviewRows(postId).single()["id"] as Long

        repeat(2) { decide(reviewId, "KEEP", note = "기준대로 유지").andExpect(status().isNoContent) }
        // 닫힌 요청에 다른 결정을 보내도 처음 결정이 남는다
        decide(reviewId, "RESTORE").andExpect(status().isNoContent)

        assertThat(reviewRows(postId).single()).containsEntry("status", "KEPT")
        assertThat(safety.postState(postId)).containsEntry("hidden", true)
        assertThat(safety.feedPostIds(viewer)).doesNotContain(postId)
        safety.awaitNotifications(author, "REVIEW_KEPT", 1)
        assertThat(safety.notificationKeys(author, "REVIEW_KEPT")).containsExactly("REVIEW:$reviewId")
        val actions = safety.actions("POST", postId)
        assertThat(actions.map { it["action"] }).containsExactly("KEEP_HIDDEN")
        assertThat(actions[0]).containsEntry("operator_id", operator.id).containsEntry("note", "기준대로 유지")
        assertThat(pendingReviews().map { it.get("reviewId").asLong() }).doesNotContain(reviewId)
        // 유지로 닫힌 뒤에는 다시 요청할 수 없고, 요청했다는 표시는 남는다
        request(author, "POST", postId).andExpect(status().isConflict)
        coreLoop.detail(author, postId).andExpect(jsonPath("$.data.safety.reviewRequested").value(true))
    }

    @Test
    fun `US4-AC3 해제로 닫으면 다시 보이고 요청은 RESTORED이며 CONTENT_RESTORED 알림이 간다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val postId = hiddenPost(author, "다시 보니 문제없는 글")
        request(author, "POST", postId).andExpect(status().isNoContent)
        val reviewId = reviewRows(postId).single()["id"] as Long

        repeat(2) { decide(reviewId, "RESTORE").andExpect(status().isNoContent) }

        assertThat(reviewRows(postId).single()).containsEntry("status", "RESTORED")
        assertThat(safety.feedPostIds(viewer)).contains(postId)
        safety.awaitNotifications(author, "CONTENT_RESTORED", 1)
        safety.awaitListenersIdle()
        assertThat(safety.notificationKeys(author, "REVIEW_KEPT")).isEmpty()
        assertThat(safety.actions("POST", postId).map { it["action"] }).containsExactly("UNHIDE")

        listOf("{}", """{"decision":"MAYBE"}""").forEach { body ->
            safety.send(operator, HttpMethod.PUT, "$REVIEWS/$reviewId/decision", body).andExpect(status().isBadRequest)
        }
        decide(Long.MAX_VALUE, "KEEP").andExpect(status().isNotFound)
    }

    @Test
    fun `운영자가 숨김을 바로 풀면 열린 요청이 RESTORED로 닫힌다`() {
        val author = members.onboarded()
        val postId = hiddenPost(author, "요청을 기다리던 글")
        request(author, "POST", postId).andExpect(status().isNoContent)

        safety
            .send(operator, HttpMethod.DELETE, "/api/v1/operator/contents/POST/$postId/hidden")
            .andExpect(status().isNoContent)

        assertThat(reviewRows(postId).single()).containsEntry("status", "RESTORED")
        assertThat(safety.postState(postId)).containsEntry("hidden", false)
    }

    @Test
    fun `요청한 글을 지우면 요청이 닫혀 운영자 조회에서 빠진다`() {
        val author = members.onboarded()
        val postId = hiddenPost(author, "요청하고 지운 글")
        request(author, "POST", postId).andExpect(status().isNoContent)
        val reviewId = reviewRows(postId).single()["id"] as Long

        coreLoop.removePost(author, postId).andExpect(status().isNoContent)

        assertThat(reviewRows(postId).single()).containsEntry("status", "KEPT")
        assertThat(pendingReviews().map { it.get("reviewId").asLong() }).doesNotContain(reviewId)
        // 지운 글의 결과는 알리지 않는다
        safety.awaitListenersIdle()
        assertThat(safety.notificationKeys(author, "REVIEW_KEPT")).isEmpty()
    }

    private fun hiddenPost(
        author: TestMember,
        content: String,
    ): Long = safety.insertPost(author, content).also { moderation.hide(ContentType.POST, it, "RISK") }

    private fun request(
        member: TestMember,
        targetType: String,
        targetId: Long,
    ): ResultActions {
        val body = mapOf("targetType" to targetType, "targetId" to targetId)
        return safety.send(member, HttpMethod.POST, PATH, body)
    }

    private fun decide(
        reviewId: Long,
        decision: String,
        note: String? = null,
    ): ResultActions =
        safety.send(
            operator,
            HttpMethod.PUT,
            "$REVIEWS/$reviewId/decision",
            mapOf("decision" to decision, "note" to note),
        )

    private fun pendingReviews() = safety.allPages(operator, REVIEWS, "size" to "50")

    private fun reviewRows(postId: Long): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("select * from review_request where post_id = ? order by id", postId)

    private companion object {
        const val PATH = "/api/v1/review-requests"
        const val REVIEWS = "/api/v1/operator/review-requests"
    }
}
