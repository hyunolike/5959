package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.post.ContentType
import com.ogu.post.PostModerationApi
import com.ogu.safety.application.SafetyPurgeJob
import com.ogu.support.MemberFixture
import com.ogu.support.SafetyFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/** T051: 1년이 지난 안전 기록을 지운다(005 research R13). 숨김 상태와 아직 열린 일은 남는다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SafetyPurgeJobTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var moderation: PostModerationApi

    @Autowired
    lateinit var purgeJob: SafetyPurgeJob

    @Test
    fun `1년이 지난 닫힌 기록만 지우고 숨김 상태, 열린 신고와 요청, 최근 기록은 남긴다`() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        val author = MemberFixture(mockMvc).onboarded()
        val safety = SafetyFixture(mockMvc, jdbcTemplate)
        val postId = safety.insertPost(author, "오래전에 숨겨진 글")
        moderation.hide(ContentType.POST, postId, "RISK")
        val old = Timestamp.from(Instant.now().minus(Duration.ofDays(366)))
        val recent = Timestamp.from(Instant.now().minus(Duration.ofDays(364)))
        // 다른 테스트의 행과 겹치지 않는 대상 ID
        val base = System.nanoTime()

        val oldAssessment = assessment(postId, author.id, "DONE", old)
        val oldPendingAssessment = assessment(postId, author.id, "PENDING", old)
        val recentAssessment = assessment(postId, author.id, "DONE", recent)
        val oldReport = report(author.id, base + 1, "RESOLVED", old)
        val oldOpenReport = report(author.id, base + 2, "PENDING", old)
        val recentReport = report(author.id, base + 3, "REJECTED", recent)
        val oldReview = review(author.id, base + 4, "KEPT", old)
        val oldOpenReview = review(author.id, base + 5, "PENDING", old)
        val oldAction = action(author.id, base + 6, old)
        val recentAction = action(author.id, base + 7, recent)

        val deleted = purgeJob.purge()

        assertThat(deleted["risk_assessment"]).isGreaterThanOrEqualTo(1)
        assertThat(deleted.keys).containsExactly("risk_assessment", "report", "review_request", "moderation_action")
        assertThat(exists("risk_assessment", oldAssessment)).isFalse()
        assertThat(exists("risk_assessment", oldPendingAssessment)).isTrue()
        assertThat(exists("risk_assessment", recentAssessment)).isTrue()
        assertThat(exists("report", oldReport)).isFalse()
        assertThat(exists("report", oldOpenReport)).isTrue()
        assertThat(exists("report", recentReport)).isTrue()
        assertThat(exists("review_request", oldReview)).isFalse()
        assertThat(exists("review_request", oldOpenReview)).isTrue()
        assertThat(exists("moderation_action", oldAction)).isFalse()
        assertThat(exists("moderation_action", recentAction)).isTrue()
        // 기록이 지워져도 글은 숨긴 채다
        assertThat(safety.postState(postId)).containsEntry("hidden", true).containsEntry("hidden_reason", "RISK")
        // 다시 돌려도 더 지울 것이 없다
        assertThat(purgeJob.purge().values).containsOnly(0)
    }

    private fun assessment(
        postId: Long,
        authorId: Long,
        status: String,
        createdAt: Timestamp,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into risk_assessment (target_type, target_id, post_id, author_id, content_version, keyword_level,
                                         level, status, next_attempt_at, created_at)
            values ('POST', ?, ?, ?, ?, 'CRISIS', 'CRISIS', ?, ?, ?)
            returning id
            """.trimIndent(),
            Long::class.java,
            postId,
            postId,
            authorId,
            createdAt,
            status,
            // 오래된 PENDING을 재시도 스케줄러가 집지 않게 먼 뒤로 둔다
            Timestamp.from(Instant.now().plus(Duration.ofDays(1))),
            createdAt,
        )!!

    private fun report(
        reporterId: Long,
        targetId: Long,
        status: String,
        createdAt: Timestamp,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into report (reporter_id, target_type, target_id, post_id, reason, status, created_at)
            values (?, 'POST', ?, ?, 'SPAM', ?, ?)
            returning id
            """.trimIndent(),
            Long::class.java,
            reporterId,
            targetId,
            targetId,
            status,
            createdAt,
        )!!

    private fun review(
        requesterId: Long,
        targetId: Long,
        status: String,
        createdAt: Timestamp,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into review_request (target_type, target_id, post_id, requester_id, status, created_at)
            values ('POST', ?, ?, ?, ?, ?)
            returning id
            """.trimIndent(),
            Long::class.java,
            targetId,
            targetId,
            requesterId,
            status,
            createdAt,
        )!!

    private fun action(
        operatorId: Long,
        targetId: Long,
        createdAt: Timestamp,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into moderation_action (operator_id, action, target_type, target_id, created_at)
            values (?, 'HIDE', 'POST', ?, ?)
            returning id
            """.trimIndent(),
            Long::class.java,
            operatorId,
            targetId,
            createdAt,
        )!!

    private fun exists(
        table: String,
        id: Long,
    ): Boolean = jdbcTemplate.queryForObject("select count(*) from $table where id = ?", Int::class.java, id) == 1
}
