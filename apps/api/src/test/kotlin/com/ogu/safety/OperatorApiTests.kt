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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** T037: 운영자 조회와 처리(005 US4, research R10). 운영자는 테스트가 DB에서 지정한다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OperatorApiTests {
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
    fun `US4-AC1 판정 조회가 최신순이고 단계, 방법, 상태가 있으며 level과 reviewed로 거른다`() {
        val crisis = coreLoop.createPost(members.onboarded(), SafetyFixture.CRISIS_TEXT)
        val concern = coreLoop.createPost(members.onboarded(), SafetyFixture.CONCERN_TEXT)
        val safe = coreLoop.createPost(members.onboarded(), SafetyFixture.SAFE_TEXT)
        safety.awaitListenersIdle()

        val all = safety.allPages(operator, ASSESSMENTS, "size" to "50")
        val ids = all.map { it.get("assessmentId").asLong() }
        assertThat(ids).isSortedAccordingTo(reverseOrder())
        // 기본은 단계가 NONE이 아닌 판정만이다
        assertThat(all.mapNotNull { postIdOf(it) }).contains(crisis, concern).doesNotContain(safe)

        val crisisItem = all.first { postIdOf(it) == crisis }
        assertThat(crisisItem.get("level").asString()).isEqualTo("CRISIS")
        assertThat(crisisItem.get("method").asString()).isEqualTo("KEYWORD")
        assertThat(crisisItem.get("status").asString()).isEqualTo("DONE")
        assertThat(crisisItem.get("reviewed").asBoolean()).isFalse()
        assertThat(crisisItem.get("createdAt").asString()).isNotBlank()
        val target = crisisItem.get("target")
        assertThat(target.get("content").asString()).isEqualTo(SafetyFixture.CRISIS_TEXT)
        assertThat(target.get("hidden").asBoolean()).isTrue()
        assertThat(target.get("deleted").asBoolean()).isFalse()

        val concerns = safety.allPages(operator, ASSESSMENTS, "level" to "CONCERN", "size" to "50")
        assertThat(concerns.map { it.get("level").asString() }).containsOnly("CONCERN")
        assertThat(concerns.mapNotNull { postIdOf(it) }).contains(concern).doesNotContain(crisis)
        val nones = safety.allPages(operator, ASSESSMENTS, "level" to "NONE", "size" to "50")
        assertThat(nones.mapNotNull { postIdOf(it) }).contains(safe).doesNotContain(crisis, concern)

        val assessmentId = crisisItem.get("assessmentId").asLong()
        repeat(2) {
            safety.send(operator, HttpMethod.PUT, "$ASSESSMENTS/$assessmentId/reviewed").andExpect(status().isNoContent)
        }
        val reviewed = safety.allPages(operator, ASSESSMENTS, "reviewed" to "true", "size" to "50")
        assertThat(reviewed.map { it.get("reviewed").asBoolean() }).containsOnly(true)
        assertThat(reviewed.mapNotNull { postIdOf(it) }).contains(crisis).doesNotContain(concern)
        val unreviewed = safety.allPages(operator, ASSESSMENTS, "reviewed" to "false", "size" to "50")
        assertThat(unreviewed.mapNotNull { postIdOf(it) }).contains(concern).doesNotContain(crisis)

        safety
            .send(operator, HttpMethod.PUT, "$ASSESSMENTS/${Long.MAX_VALUE}/reviewed")
            .andExpect(status().isNotFound)
    }

    @Test
    fun `US4-AC2 신고 조회에 사유, 설명, 같은 대상의 열린 신고 수가 있다`() {
        val author = members.onboarded()
        val postId = safety.insertPost(author, "신고가 둘 쌓인 글")
        val first = members.onboarded()
        val second = members.onboarded()
        report(first, "POST", postId, "ABUSIVE")
        report(second, "POST", postId, "OTHER", "광고 같아요")

        val mine = safety.allPages(operator, REPORTS, "size" to "50").filter { postIdOf(it) == postId }

        assertThat(mine).hasSize(2)
        assertThat(mine.map { it.get("reporterId").asLong() }).containsExactly(second.id, first.id)
        assertThat(mine.map { it.get("reason").asString() }).containsExactly("OTHER", "ABUSIVE")
        assertThat(mine[0].get("detail").asString()).isEqualTo("광고 같아요")
        assertThat(mine[1].get("detail").isNull).isTrue()
        assertThat(mine.map { it.get("openReportCount").asInt() }).containsOnly(2)
        assertThat(mine.map { it.get("status").asString() }).containsOnly("PENDING")
        val target = mine[0].get("target")
        assertThat(target.get("content").asString()).isEqualTo("신고가 둘 쌓인 글")
        assertThat(target.get("authorId").asLong()).isEqualTo(author.id)
        assertThat(target.get("hidden").asBoolean()).isFalse()
    }

    @Test
    fun `US4-AC3 숨김을 풀면 다른 회원에게 다시 보이고 CONTENT_RESTORED 알림이 간다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val postId = safety.insertPost(author, "잘못 가려진 글")
        moderation.hide(ContentType.POST, postId, "RISK")
        assertThat(safety.feedPostIds(viewer)).doesNotContain(postId)

        repeat(2) {
            safety.send(operator, HttpMethod.DELETE, hiddenPath("POST", postId)).andExpect(status().isNoContent)
        }

        assertThat(safety.feedPostIds(viewer)).contains(postId)
        assertThat(safety.postState(postId)).containsEntry("hidden", false)
        // 이미 풀린 것을 다시 풀어도 기록과 알림은 하나다
        val actions = safety.actions("POST", postId)
        assertThat(actions.map { it["action"] }).containsExactly("UNHIDE")
        safety.awaitNotifications(author, "CONTENT_RESTORED", 1)
        assertThat(safety.notificationKeys(author, "CONTENT_RESTORED"))
            .containsExactly("RESTORED:POST:$postId:${actions[0]["id"]}")
    }

    @Test
    fun `US4-AC3 숨긴 댓글을 풀면 댓글 목록에 다시 보이고 작성자에게 알림이 간다`() {
        val postAuthor = members.onboarded()
        val commenter = members.onboarded()
        val postId = safety.insertPost(postAuthor, "댓글이 달린 글")
        val commentId = coreLoop.comment(commenter, postId, "가려졌다 풀릴 댓글")
        safety.send(operator, HttpMethod.PUT, hiddenPath("COMMENT", commentId)).andExpect(status().isNoContent)
        assertThat(safety.commentState(commentId))
            .containsEntry("hidden", true)
            .containsEntry("hidden_reason", "OPERATOR")

        safety.send(operator, HttpMethod.DELETE, hiddenPath("COMMENT", commentId)).andExpect(status().isNoContent)

        coreLoop
            .comments(postAuthor, postId)
            .andExpect(jsonPath("$.data.items[0].hidden").value(false))
            .andExpect(jsonPath("$.data.items[0].content").value("가려졌다 풀릴 댓글"))
        safety.awaitNotifications(commenter, "CONTENT_RESTORED", 1)
        assertThat(safety.actions("COMMENT", commentId).map { it["action"] }).containsExactly("HIDE", "UNHIDE")
    }

    @Test
    fun `US4-AC4 숨기면 그 대상의 열린 신고가 모두 RESOLVED로 닫힌다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val postId = safety.insertPost(author, "신고를 받아 숨길 글")
        val other = safety.insertPost(author, "함께 신고됐지만 그대로 둘 글")
        report(members.onboarded(), "POST", postId, "ABUSIVE")
        report(viewer, "POST", postId, "SPAM")
        report(viewer, "POST", other, "SPAM")

        repeat(2) {
            safety
                .send(operator, HttpMethod.PUT, hiddenPath("POST", postId), mapOf("note" to "신고 확인"))
                .andExpect(status().isNoContent)
        }

        assertThat(safety.postState(postId)).containsEntry("hidden", true).containsEntry("hidden_reason", "OPERATOR")
        assertThat(safety.feedPostIds(viewer)).contains(other).doesNotContain(postId)
        assertThat(reportStatuses(postId)).containsExactly("RESOLVED", "RESOLVED")
        assertThat(reportStatuses(other)).containsExactly("PENDING")
        assertThat(safety.actions("POST", postId).map { it["action"] }).containsExactly("HIDE")
    }

    @Test
    fun `US4-AC5 기각하면 REJECTED로 닫히고 대상은 그대로`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val postId = safety.insertPost(author, "문제없는 글")
        report(viewer, "POST", postId, "SPAM")
        report(members.onboarded(), "POST", postId, "ABUSIVE")
        val (rejected, resolved) = reportIds(postId)

        repeat(2) {
            safety
                .send(operator, HttpMethod.PUT, "$REPORTS/$rejected/decision", mapOf("decision" to "REJECT"))
                .andExpect(status().isNoContent)
        }
        // 이미 닫힌 신고에 다른 결정을 보내도 처음 결정이 남는다
        safety
            .send(operator, HttpMethod.PUT, "$REPORTS/$rejected/decision", mapOf("decision" to "RESOLVE"))
            .andExpect(status().isNoContent)
        safety
            .send(operator, HttpMethod.PUT, "$REPORTS/$resolved/decision", mapOf("decision" to "RESOLVE"))
            .andExpect(status().isNoContent)

        assertThat(reportStatuses(postId)).containsExactly("REJECTED", "RESOLVED")
        assertThat(safety.postState(postId)).containsEntry("hidden", false)
        assertThat(safety.feedPostIds(viewer)).contains(postId)
        assertThat(safety.actions("POST", postId).map { it["action"] })
            .containsExactly("REJECT_REPORT", "RESOLVE_REPORT")
        val closed = safety.allPages(operator, REPORTS, "status" to "REJECTED", "size" to "50")
        assertThat(closed.map { it.get("reportId").asLong() }).contains(rejected).doesNotContain(resolved)

        listOf("{}", """{"decision":"MAYBE"}""").forEach { body ->
            safety
                .send(operator, HttpMethod.PUT, "$REPORTS/$rejected/decision", body)
                .andExpect(status().isBadRequest)
        }
        safety
            .send(operator, HttpMethod.PUT, "$REPORTS/${Long.MAX_VALUE}/decision", mapOf("decision" to "REJECT"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `US4-AC6 운영자가 아니면 모든 운영자 경로가 404 NOT_FOUND`() {
        val member = members.onboarded()
        val postId = safety.insertPost(members.onboarded(), "운영자만 숨길 수 있는 글")
        val calls =
            listOf(
                Triple(HttpMethod.GET, ASSESSMENTS, null),
                Triple(HttpMethod.PUT, "$ASSESSMENTS/1/reviewed", null),
                Triple(HttpMethod.GET, REPORTS, null),
                Triple(HttpMethod.PUT, "$REPORTS/1/decision", """{"decision":"REJECT"}"""),
                Triple(HttpMethod.GET, REVIEWS, null),
                Triple(HttpMethod.PUT, "$REVIEWS/1/decision", """{"decision":"KEEP"}"""),
                Triple(HttpMethod.PUT, hiddenPath("POST", postId), null),
                Triple(HttpMethod.DELETE, hiddenPath("POST", postId), null),
                Triple(HttpMethod.GET, TERMS, null),
                Triple(HttpMethod.POST, TERMS, """{"kind":"PROFANITY","term":"아무낱말"}"""),
                Triple(HttpMethod.DELETE, "$TERMS/1", null),
                // 입력이 잘못됐어도 400이 먼저 나가 경로가 드러나는 일이 없다
                Triple(HttpMethod.GET, "$ASSESSMENTS?level=WRONG", null),
                Triple(HttpMethod.PUT, hiddenPath("MEMBER", postId), null),
            )

        calls.forEach { (method, path, body) ->
            safety
                .send(member, method, path, body)
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
        }
        assertThat(safety.postState(postId)).containsEntry("hidden", false)
        mockMvc.perform(get(ASSESSMENTS)).andExpect(status().isUnauthorized)

        // 권한을 주면 같은 토큰으로 바로 통하고, 거두면 바로 404다
        safety.grantOperator(member)
        safety.get(member, ASSESSMENTS).andExpect(status().isOk)
        safety.revokeOperator(member)
        safety.get(member, ASSESSMENTS).andExpect(status().isNotFound)
    }

    @Test
    fun `US4-AC7 처리마다 moderation_action에 운영자와 시각이 남는다`() {
        val postId = safety.insertPost(members.onboarded(), "숨겼다 풀 글")
        val second = members.onboarded().also(safety::grantOperator)

        safety
            .send(operator, HttpMethod.PUT, hiddenPath("POST", postId), mapOf("note" to "  욕설 신고 확인  "))
            .andExpect(status().isNoContent)
        safety.send(second, HttpMethod.DELETE, hiddenPath("POST", postId)).andExpect(status().isNoContent)

        val actions = safety.actions("POST", postId)
        assertThat(actions.map { it["action"] }).containsExactly("HIDE", "UNHIDE")
        assertThat(actions.map { it["operator_id"] }).containsExactly(operator.id, second.id)
        assertThat(actions.map { it["note"] }).containsExactly("욕설 신고 확인", null)
        assertThat(actions.map { it["created_at"] }).doesNotContainNull()

        safety
            .send(operator, HttpMethod.PUT, hiddenPath("POST", postId), mapOf("note" to "가".repeat(201)))
            .andExpect(status().isBadRequest)
        assertThat(safety.postState(postId)).containsEntry("hidden", false)
    }

    @Test
    fun `이어 불러오기에 중복과 누락이 없고 잘못된 커서와 크기는 400이다`() {
        val reporter = members.onboarded()
        val author = members.onboarded()
        val postIds = (1..5).map { safety.insertPost(author, "신고된 글 $it") }
        postIds.forEach { report(reporter, "POST", it, "SPAM") }

        val paged = safety.allPages(operator, REPORTS, "size" to "2")

        val mine = paged.filter { it.get("reporterId").asLong() == reporter.id }.mapNotNull { postIdOf(it) }
        assertThat(mine).containsExactlyElementsOf(postIds.reversed())
        assertThat(paged.map { it.get("reportId").asLong() }).doesNotHaveDuplicates()

        listOf("cursor" to "not-a-cursor", "size" to "0", "size" to "51", "status" to "OPEN").forEach { param ->
            safety.get(operator, REPORTS, param).andExpect(status().isBadRequest)
        }
        safety.get(operator, ASSESSMENTS, "level" to "WRONG").andExpect(status().isBadRequest)
        safety.get(operator, REVIEWS, "size" to "0").andExpect(status().isBadRequest)
    }

    @Test
    fun `작성자가 지운 글은 숨김을 풀어도 보이지 않고 조회에는 본문 없이 나온다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val postId = coreLoop.createPost(author, SafetyFixture.CRISIS_TEXT)
        val reported = safety.insertPost(author, "신고된 뒤 지워진 글")
        report(viewer, "POST", reported, "SPAM")
        coreLoop.removePost(author, postId).andExpect(status().isNoContent)
        coreLoop.removePost(author, reported).andExpect(status().isNoContent)

        safety.send(operator, HttpMethod.DELETE, hiddenPath("POST", postId)).andExpect(status().isNotFound)
        safety.send(operator, HttpMethod.PUT, hiddenPath("POST", reported)).andExpect(status().isNotFound)

        assertThat(safety.feedPostIds(viewer)).doesNotContain(postId, reported)
        assertThat(safety.actions("POST", postId)).isEmpty()
        val assessment = safety.allPages(operator, ASSESSMENTS, "size" to "50").first { postIdOf(it) == postId }
        assertThat(assessment.get("target").get("deleted").asBoolean()).isTrue()
        assertThat(assessment.get("target").get("content").isNull).isTrue()
        assertThat(assessment.get("target").get("authorId").asLong()).isEqualTo(author.id)
        // 대상이 지워지면 신고는 CLOSED로 닫힌다
        val closed = safety.allPages(operator, REPORTS, "status" to "CLOSED", "size" to "50")
        val report = closed.first { postIdOf(it) == reported }
        assertThat(report.get("target").get("content").isNull).isTrue()
        assertThat(report.get("target").get("authorId").isNull).isTrue()
        assertThat(report.get("openReportCount").asInt()).isZero()
    }

    @Test
    fun `숨김과 해제를 동시에 불러도 상태와 기록이 어긋나지 않는다`() {
        val postId = safety.insertPost(members.onboarded(), "동시에 숨기고 푸는 글")
        val pool = Executors.newFixedThreadPool(CONCURRENCY)
        val start = CountDownLatch(1)

        val statuses =
            try {
                (0 until CONCURRENCY)
                    .map { index ->
                        pool.submit(
                            Callable {
                                start.await()
                                val method = if (index % 2 == 0) HttpMethod.PUT else HttpMethod.DELETE
                                safety
                                    .send(operator, method, hiddenPath("POST", postId))
                                    .andReturn()
                                    .response.status
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertThat(statuses).containsOnly(204)
        // 기록은 숨김으로 시작해 번갈아 이어지고, 마지막 기록이 지금 상태다
        val actions = safety.actions("POST", postId).map { it["action"] as String }
        assertThat(actions).isNotEmpty()
        val alternating = actions.indices.map { if (it % 2 == 0) "HIDE" else "UNHIDE" }
        assertThat(actions).containsExactlyElementsOf(alternating)
        assertThat(safety.postState(postId)).containsEntry("hidden", actions.last() == "HIDE")
    }

    @Test
    fun `낱말 더하기와 빼기가 정규화해 저장하고 기록을 남기며 바로 판정에 쓰인다`() {
        val added =
            coreLoop.data(
                safety
                    .send(operator, HttpMethod.POST, TERMS, mapOf("kind" to "CRISIS", "term" to " 오구 검증-낱말!! "))
                    .andExpect(status().isOk),
            )
        val termId = added.get("termId").asLong()
        try {
            assertThat(added.get("term").asString()).isEqualTo("오구검증낱말")
            assertThat(added.get("kind").asString()).isEqualTo("CRISIS")
            // 같은 낱말을 다시 더하면 그대로 돌려준다
            val again =
                coreLoop.data(
                    safety
                        .send(operator, HttpMethod.POST, TERMS, mapOf("kind" to "CRISIS", "term" to "오구검증낱말"))
                        .andExpect(status().isOk),
                )
            assertThat(again.get("termId").asLong()).isEqualTo(termId)
            val listed = safety.data(operator, TERMS, "kind" to "CRISIS").values()
            assertThat(listed.map { it.get("kind").asString() }).containsOnly("CRISIS")
            assertThat(listed.map { it.get("term").asString() }).contains("오구검증낱말", "자살")
            assertThat(safety.data(operator, TERMS).values().map { it.get("kind").asString() })
                .contains("CRISIS", "CONCERN", "PROFANITY", "ALLOW")

            val hidden = coreLoop.createPost(members.onboarded(), "오늘은 오구 검증 낱말 이 든 글을 씁니다")
            assertThat(safety.postState(hidden)).containsEntry("hidden", true).containsEntry("risk_level", "CRISIS")
        } finally {
            repeat(2) { safety.send(operator, HttpMethod.DELETE, "$TERMS/$termId").andExpect(status().isNoContent) }
        }

        val visible = coreLoop.createPost(members.onboarded(), "오늘은 오구 검증 낱말 이 든 글을 다시 씁니다")
        assertThat(safety.postState(visible)).containsEntry("hidden", false).containsEntry("risk_level", "NONE")
        val actions = safety.actions("TERM", termId)
        assertThat(actions.map { it["action"] }).containsExactly("ADD_TERM", "REMOVE_TERM")
        assertThat(actions.map { it["operator_id"] }).containsOnly(operator.id)

        listOf(
            """{"kind":"CRISIS","term":"가"}""",
            // 21글자. 같은 글자를 되풀이하면 정규화가 두 글자로 줄이므로 서로 다른 글자로 채운다
            """{"kind":"CRISIS","term":"가나다라마바사아자차카타파하거너더러머버서"}""",
            """{"kind":"CRISIS","term":"!!!"}""",
            """{"kind":"CRISIS"}""",
            """{"term":"낱말"}""",
            """{"kind":"NICE","term":"낱말"}""",
        ).forEach { body -> safety.send(operator, HttpMethod.POST, TERMS, body).andExpect(status().isBadRequest) }
        safety.get(operator, TERMS, "kind" to "NICE").andExpect(status().isBadRequest)
    }

    private fun report(
        reporter: TestMember,
        targetType: String,
        targetId: Long,
        reason: String,
        detail: String? = null,
    ) {
        val body = mapOf("targetType" to targetType, "targetId" to targetId, "reason" to reason, "detail" to detail)
        safety.send(reporter, HttpMethod.POST, "/api/v1/reports", body).andExpect(status().isNoContent)
    }

    private fun reportIds(postId: Long): List<Long> =
        jdbcTemplate.queryForList("select id from report where post_id = ? order by id", Long::class.java, postId)

    private fun reportStatuses(postId: Long): List<String> =
        jdbcTemplate.queryForList("select status from report where post_id = ? order by id", String::class.java, postId)

    /** 대상이 글인 항목의 글 ID. */
    private fun postIdOf(item: JsonNode): Long? =
        item
            .get("target")
            .takeIf { it.get("targetType").asString() == "POST" }
            ?.get("targetId")
            ?.asLong()

    private fun hiddenPath(
        targetType: String,
        targetId: Long,
    ) = "/api/v1/operator/contents/$targetType/$targetId/hidden"

    private companion object {
        const val ASSESSMENTS = "/api/v1/operator/assessments"
        const val REPORTS = "/api/v1/operator/reports"
        const val REVIEWS = "/api/v1/operator/review-requests"
        const val TERMS = "/api/v1/operator/terms"
        const val CONCURRENCY = 8
    }
}
