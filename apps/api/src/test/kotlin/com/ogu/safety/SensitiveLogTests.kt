package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SafetyFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T055: 본문, 걸린 표현, 신고 설명, 운영자 메모가 로그와 이벤트 발행 기록에 남지 않는다(005 FR-015, SC-008, research R13).
 * 판정, 댓글, 신고, 운영자 조회와 처리, 재검토 요청, 잘못된 요청까지 한 번씩 거친 뒤 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class SensitiveLogTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Test
    fun `위기 글이 판정, 신고, 운영자 처리를 거쳐도 로그와 이벤트 기록에 본문이 없다`(output: CapturedOutput) {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        val members = MemberFixture(mockMvc)
        val coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        val safety = SafetyFixture(mockMvc, jdbcTemplate)
        val author = members.onboarded()
        val reporter = members.onboarded()
        val operator = members.onboarded().also(safety::grantOperator)

        // 판정: 위기 글과 위기 댓글, AI 분류 실패와 재시도
        val crisis = coreLoop.createPost(author, "$POST_MARKER ${SafetyFixture.CRISIS_TEXT}")
        val failing = coreLoop.createPost(author, "[위험분류실패:1] $POST_MARKER ${SafetyFixture.CONCERN_TEXT}")
        val visible = coreLoop.createPost(author, "$POST_MARKER 병신 같은 하루")
        val commentId = coreLoop.comment(reporter, visible, "$COMMENT_MARKER 죽고 싶다")
        // 신고와 재검토 요청
        val target = mapOf("targetType" to "POST", "targetId" to visible)
        val report = target + mapOf("reason" to "OTHER", "detail" to DETAIL_MARKER)
        safety.send(reporter, HttpMethod.POST, "/api/v1/reports", report).andExpect(status().isNoContent)
        val review = mapOf("targetType" to "POST", "targetId" to crisis)
        safety.send(author, HttpMethod.POST, "/api/v1/review-requests", review).andExpect(status().isNoContent)
        // 운영자 조회와 처리
        safety.allPages(operator, "$OPERATOR/assessments", "size" to "50")
        safety.allPages(operator, "$OPERATOR/reports", "size" to "50")
        safety.allPages(operator, "$OPERATOR/review-requests", "size" to "50")
        val note = mapOf("note" to NOTE_MARKER)
        safety.send(operator, HttpMethod.PUT, "$OPERATOR/contents/POST/$visible/hidden", note)
        safety.send(operator, HttpMethod.DELETE, "$OPERATOR/contents/POST/$crisis/hidden")
        safety.send(operator, HttpMethod.DELETE, "$OPERATOR/contents/COMMENT/$commentId/hidden")
        // 잘못된 요청과 다른 회원의 조회
        safety.send(reporter, HttpMethod.POST, "/api/v1/reports", """{"targetType":"POST","detail":"$DETAIL_MARKER"""")
        safety.send(operator, HttpMethod.POST, "$OPERATOR/terms", """{"kind":"WRONG","term":"$NOTE_MARKER"}""")
        safety.feedPostIds(reporter)
        coreLoop.detail(reporter, crisis)
        coreLoop.comments(author, visible)
        safety.awaitNotifications(author, "CONTENT_RESTORED", 1)
        safety.awaitListenersIdle()

        assertThat(safety.postState(failing)).containsEntry("risk_level", "CONCERN")
        val log = output.all
        listOf(POST_MARKER, COMMENT_MARKER, DETAIL_MARKER, NOTE_MARKER, "죽고 싶", "죽고싶", "다 포기하고", "병신")
            .forEach { assertThat(log).doesNotContain(it) }
        // 로그가 실제로 잡히고 있다
        assertThat(log).contains("com.ogu")

        val events =
            jdbcTemplate.queryForList(
                """
                select serialized_event from event_publication
                where serialized_event like '%"postId":' || ? || '%' or serialized_event like '%"postId":' || ? || '%'
                """.trimIndent(),
                String::class.java,
                crisis.toString(),
                visible.toString(),
            )
        assertThat(events).isNotEmpty()
        events.forEach { event ->
            assertThat(event).doesNotContain(POST_MARKER, COMMENT_MARKER, "죽고", "병신", "content")
        }
    }

    private companion object {
        const val OPERATOR = "/api/v1/operator"
        const val POST_MARKER = "오구본문표지"
        const val COMMENT_MARKER = "오구댓글표지"
        const val DETAIL_MARKER = "오구신고설명표지"
        const val NOTE_MARKER = "오구운영자메모표지"
    }
}
