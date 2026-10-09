package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.post.ContentType
import com.ogu.post.PostModerationApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** T032: 글과 댓글 신고(005 US3, research R9). */
@SpringBootTest
@Import(TestcontainersConfiguration::class, ReportApiTests.ClockOverride::class)
class ReportApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var moderation: PostModerationApi

    @Autowired
    lateinit var clock: MutableClock

    private val jsonMapper = JsonMapper.builder().build()
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
    fun `US3-AC1 사유와 함께 신고하면 204이고 기록된다`() {
        val author = members.onboarded()
        val reporter = members.onboarded()
        val postId = safety.insertPost(author, "신고당할 글")
        val commentId = coreLoop.comment(author, postId, "신고당할 댓글")

        report(reporter, "POST", postId, "ABUSIVE").andExpect(status().isNoContent)
        report(reporter, "COMMENT", commentId, "OTHER", "가".repeat(200)).andExpect(status().isNoContent)

        val rows = reportsBy(reporter)
        assertThat(rows).hasSize(2)
        assertThat(rows[0])
            .containsEntry("target_type", "POST")
            .containsEntry("target_id", postId)
            .containsEntry("post_id", postId)
            .containsEntry("reason", "ABUSIVE")
            .containsEntry("status", "PENDING")
        assertThat(rows[0]["detail"]).isNull()
        assertThat(rows[1])
            .containsEntry("target_type", "COMMENT")
            .containsEntry("target_id", commentId)
            .containsEntry("post_id", postId)
            .containsEntry("detail", "가".repeat(200))
    }

    @Test
    fun `설명은 기타일 때만 받고 200글자를 넘으면 400이다`() {
        val author = members.onboarded()
        val reporter = members.onboarded()
        val first = safety.insertPost(author, "글 1")
        val second = safety.insertPost(author, "글 2")

        report(reporter, "POST", first, "OTHER", "가".repeat(201))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        // 다른 사유에 딸려 온 설명은 버린다
        report(reporter, "POST", second, "SPAM", "함께 보낸 설명").andExpect(status().isNoContent)

        assertThat(reportsBy(reporter)).hasSize(1)
        assertThat(reportsBy(reporter)[0]["detail"]).isNull()
    }

    @Test
    fun `대상의 종류, 대상, 사유가 빠졌거나 목록 밖의 값이면 400이다`() {
        val reporter = members.onboarded()

        listOf(
            "{}",
            """{"targetType":"POST","targetId":1}""",
            """{"targetType":"POST","reason":"SPAM"}""",
            """{"targetId":1,"reason":"SPAM"}""",
            """{"targetType":"MEMBER","targetId":1,"reason":"SPAM"}""",
            """{"targetType":"POST","targetId":1,"reason":"BORING"}""",
        ).forEach { body ->
            mockMvc
                .perform(
                    post(PATH).bearer(reporter.accessToken).contentType(MediaType.APPLICATION_JSON).content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `US3-AC2 같은 대상을 다시 신고하면 409 ALREADY_REPORTED이고 수가 늘지 않는다`() {
        val author = members.onboarded()
        val reporter = members.onboarded()
        val postId = safety.insertPost(author, "두 번 신고당할 글")
        report(reporter, "POST", postId, "SPAM").andExpect(status().isNoContent)

        report(reporter, "POST", postId, "ABUSIVE")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("ALREADY_REPORTED"))

        assertThat(reportsBy(reporter)).hasSize(1)
        assertThat(reportsBy(reporter)[0]).containsEntry("reason", "SPAM")
    }

    @Test
    fun `US3-AC3 자기 글과 댓글은 403 CANNOT_REPORT_OWN_CONTENT`() {
        val author = members.onboarded()
        val postId = safety.insertPost(author, "내 글")
        val commentId = coreLoop.comment(author, postId, "내 댓글")

        report(author, "POST", postId, "SPAM")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CANNOT_REPORT_OWN_CONTENT"))
        report(author, "COMMENT", commentId, "SPAM")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CANNOT_REPORT_OWN_CONTENT"))

        assertThat(reportsBy(author)).isEmpty()
    }

    @Test
    fun `US3-AC4 글 상세, 댓글, 피드, 알림 어디에도 신고 수나 신고 여부가 없다`() {
        val author = members.onboarded()
        val reporter = members.onboarded()
        val postId = coreLoop.postWithoutMonster(author)
        val commentId = coreLoop.comment(author, postId, "평범한 댓글")
        report(reporter, "POST", postId, "DANGEROUS").andExpect(status().isNoContent)
        report(reporter, "COMMENT", commentId, "ABUSIVE").andExpect(status().isNoContent)
        safety.awaitListenersIdle()

        val responses =
            listOf(
                coreLoop.detail(author, postId),
                coreLoop.detail(reporter, postId),
                coreLoop.comments(author, postId),
                safety.get(author, "/api/v1/feed"),
                safety.get(author, "/api/v1/members/me/posts"),
                safety.get(author, "/api/v1/notifications"),
            ).map {
                it
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            }

        assertThat(responses).allSatisfy { body ->
            assertThat(body.lowercase()).doesNotContain("report").doesNotContain("신고")
        }
        // 신고당한 회원에게 알림이 가지 않는다
        val types =
            jdbcTemplate.queryForList(
                "select type from notification where receiver_id = ?",
                String::class.java,
                author.id,
            )
        assertThat(types).isEmpty()
    }

    @Test
    fun `US3-AC5 한 시간에 21번째는 429 REPORT_RATE_LIMITED와 Retry-After이고, 한 시간이 지나면 다시 된다`() {
        val author = members.onboarded()
        val reporter = members.onboarded()
        val posts = (1..22).map { safety.insertPost(author, "한도 확인용 글 $it") }
        // 시계를 한 시간 돌리면 access 토큰(15분)이 끝나므로, 지난 신고는 시각을 정해 직접 넣는다
        val now = clock.instant()
        insertReport(reporter, posts[0], now.minus(Duration.ofMinutes(50)))
        posts.subList(1, 20).forEach { insertReport(reporter, it, now.minus(Duration.ofMinutes(1))) }

        // 가장 이른 신고가 60분 전이 되는 때까지 남은 10분
        report(reporter, "POST", posts[20], "SPAM")
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error.code").value("REPORT_RATE_LIMITED"))
            .andExpect(header().string("Retry-After", "600"))
            .andExpect(jsonPath("$.error.retryAfterSeconds").value(600))
        assertThat(reportsBy(reporter)).hasSize(20)

        // 가장 이른 신고가 한 시간을 넘기면 한 건을 더 낼 수 있다
        clock.advance(Duration.ofMinutes(10))
        report(reporter, "POST", posts[20], "SPAM").andExpect(status().isNoContent)
        // 나머지 19건과 방금 낸 것은 아직 한 시간 안이라 다시 막힌다
        report(reporter, "POST", posts[21], "SPAM").andExpect(status().isTooManyRequests)
    }

    @Test
    fun `같은 회원의 동시 신고 30개 가운데 20개만 통과한다`() {
        val author = members.onboarded()
        val reporter = members.onboarded()
        val posts = (1..30).map { safety.insertPost(author, "동시 신고 $it") }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(10)
        try {
            val futures =
                posts.map { postId ->
                    executor.submit(
                        Callable {
                            start.await()
                            report(reporter, "POST", postId, "SPAM").andReturn().response.status
                        },
                    )
                }
            start.countDown()
            val statuses = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertThat(statuses.count { it == 204 }).isEqualTo(20)
            assertThat(statuses.count { it == 429 }).isEqualTo(10)
        } finally {
            executor.shutdownNow()
        }
        assertThat(reportsBy(reporter)).hasSize(20)
    }

    @Test
    fun `US3-AC6 여러 회원이 신고해도 글은 그대로 보인다`() {
        val author = members.onboarded()
        val postId = safety.insertPost(author, "여럿이 신고한 글")
        val reporters = (1..6).map { members.onboarded() }

        reporters.forEach { report(it, "POST", postId, "ABUSIVE").andExpect(status().isNoContent) }

        assertThat(safety.postState(postId)).containsEntry("hidden", false)
        coreLoop.detail(reporters.first(), postId).andExpect(status().isOk)
        assertThat(safety.feedPostIds(reporters.first())).contains(postId)
    }

    @Test
    fun `숨겼거나 지웠거나 없는 대상은 404다`() {
        val author = members.onboarded()
        val reporter = members.onboarded()
        val hiddenPost = safety.insertPost(author, "숨긴 글")
        val commentOnHiddenPost = coreLoop.comment(author, hiddenPost, "숨긴 글의 댓글")
        moderation.hide(ContentType.POST, hiddenPost, "OPERATOR")
        val visiblePost = safety.insertPost(author, "보이는 글")
        val hiddenComment = coreLoop.comment(author, visiblePost, "숨긴 댓글")
        moderation.hide(ContentType.COMMENT, hiddenComment, "OPERATOR")
        val deletedPost = safety.insertPost(author, "지운 글")
        coreLoop.deletePost(deletedPost)

        report(reporter, "POST", hiddenPost, "SPAM")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        report(reporter, "POST", deletedPost, "SPAM").andExpect(status().isNotFound)
        report(reporter, "POST", Long.MAX_VALUE, "SPAM").andExpect(status().isNotFound)
        report(reporter, "COMMENT", commentOnHiddenPost, "SPAM")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        report(reporter, "COMMENT", hiddenComment, "SPAM").andExpect(status().isNotFound)
        assertThat(reportsBy(reporter)).isEmpty()
    }

    @Test
    fun `대상이 지워지면 열린 신고가 CLOSED로 닫힌다`() {
        val author = members.onboarded()
        val reporter = members.onboarded()
        val postId = coreLoop.postWithoutMonster(author)
        val rootComment = coreLoop.comment(author, postId, "원 댓글")
        val reply = coreLoop.comment(author, postId, "답글", parentId = rootComment)
        val otherPost = coreLoop.postWithoutMonster(author)
        val commentOnOtherPost = coreLoop.comment(author, otherPost, "곧 지워질 글의 댓글")
        report(reporter, "COMMENT", rootComment, "ABUSIVE").andExpect(status().isNoContent)
        report(reporter, "COMMENT", reply, "ABUSIVE").andExpect(status().isNoContent)
        report(reporter, "POST", otherPost, "SPAM").andExpect(status().isNoContent)
        report(reporter, "COMMENT", commentOnOtherPost, "SPAM").andExpect(status().isNoContent)
        report(reporter, "POST", postId, "SPAM").andExpect(status().isNoContent)

        // 원 댓글을 지우면 답글의 신고도, 글을 지우면 그 글의 댓글 신고도 닫힌다
        coreLoop.removeComment(author, rootComment).andExpect(status().isNoContent)
        coreLoop.removePost(author, otherPost).andExpect(status().isNoContent)

        val statuses = reportsBy(reporter).associate { "${it["target_type"]}:${it["target_id"]}" to it["status"] }
        assertThat(statuses)
            .containsEntry("COMMENT:$rootComment", "CLOSED")
            .containsEntry("COMMENT:$reply", "CLOSED")
            .containsEntry("POST:$otherPost", "CLOSED")
            .containsEntry("COMMENT:$commentOnOtherPost", "CLOSED")
            .containsEntry("POST:$postId", "PENDING")
    }

    @Test
    fun `온보딩 전 회원은 403, 토큰이 없으면 401`() {
        report(members.signedUp(), "POST", 1, "SPAM")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        mockMvc
            .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
    }

    private fun report(
        reporter: TestMember,
        targetType: String,
        targetId: Long,
        reason: String,
        detail: String? = null,
    ): ResultActions {
        val body = mapOf("targetType" to targetType, "targetId" to targetId, "reason" to reason, "detail" to detail)
        return mockMvc.perform(
            post(PATH)
                .bearer(reporter.accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)),
        )
    }

    private fun insertReport(
        reporter: TestMember,
        postId: Long,
        at: Instant,
    ) {
        jdbcTemplate.update(
            "insert into report (reporter_id, target_type, target_id, post_id, reason, status, created_at) " +
                "values (?, 'POST', ?, ?, 'SPAM', 'PENDING', ?)",
            reporter.id,
            postId,
            postId,
            Timestamp.from(at),
        )
    }

    private fun reportsBy(reporter: TestMember): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("select * from report where reporter_id = ? order by id", reporter.id)

    @TestConfiguration(proxyBeanMethods = false)
    class ClockOverride {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS))
    }

    private companion object {
        const val PATH = "/api/v1/reports"
    }
}
