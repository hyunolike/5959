package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.ai.infrastructure.FakeCompanionCommentWriter
import com.ogu.safety.application.RiskClassificationRunner
import com.ogu.support.CompanionSettings
import com.ogu.support.CompanionTestConfiguration
import com.ogu.support.FutureClockSchedulers
import com.ogu.support.SafetyFixture.Companion.CONCERN_TEXT
import com.ogu.support.SafetyFixture.Companion.CRISIS_TEXT
import com.ogu.support.ScriptedRiskClassifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration

/**
 * 위험한 글과 위험한 답은 AI 친구가 건너뛴다(009 US3, research R4, R6). 글의 위험은 가짜 분류기의 표지(`[위기]`, `[우려]`,
 * `[위험분류실패:N]`)로, AI의 답은 가짜 쓰기의 표지(`[AI댓글욕설]` 등)나 정해 둔 답으로 만든다.
 */
@SpringBootTest(
    properties = [
        CompanionSettings.ENABLED,
        CompanionSettings.NO_SCHEDULER,
        CompanionSettings.LONG_TOKEN,
        CompanionSettings.NO_LIMIT,
        FutureClockSchedulers.REPORT,
        FutureClockSchedulers.RAID,
        FutureClockSchedulers.EMOTION,
        FutureClockSchedulers.SAFETY,
        FutureClockSchedulers.RECOMMEND,
        FutureClockSchedulers.RECOMMEND_BACKFILL,
    ],
)
@Import(TestcontainersConfiguration::class, CompanionTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class CompanionSafetyTests : CompanionTestBase() {
    @Autowired
    lateinit var riskRunner: RiskClassificationRunner

    @Autowired
    lateinit var riskClassifier: ScriptedRiskClassifier

    @Test
    fun `US3-AC1 위기나 우려로 판정된 글에는 반응하지 않고 AI를 부르지 않는다`() {
        val author = members.onboarded()
        val crisis = postOf(author, "[실패] [위기] 목록에 없는 표현")
        val concern = postOf(author, "[실패] [우려] 목록에 없는 표현")
        val byKeyword = postOf(author, "[실패] $CONCERN_TEXT")

        companions.pass(DEFAULT_WAIT)
        listOf(crisis, concern, byKeyword).forEach { assertThat(runner.attempt(it)).isTrue() }

        // 위기 글은 숨겨져 있어 사라진 글로 닫힌다
        assertThat(skipReasonOf(crisis)).isEqualTo("GONE")
        assertThat(skipReasonOf(concern)).isEqualTo("RISK")
        assertThat(skipReasonOf(byKeyword)).isEqualTo("RISK")
        listOf(crisis, concern, byKeyword).forEach { postId ->
            assertThat(statusOf(postId)).isEqualTo("SKIPPED")
            assertThat(companions.comments(postId)).isEmpty()
            assertThat(companions.likers(postId)).isEmpty()
            assertThat(writer.callsOf(postId)).isZero()
        }
    }

    @Test
    fun `US3-AC2 지웠거나 숨긴 글에는 반응하지 않는다`() {
        val author = members.onboarded()
        val removed = postOf(author)
        val hidden = postOf(author)
        loop.removePost(author, removed).andExpect(status().isNoContent)
        jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'OPERATOR' where id = ?", hidden)

        companions.pass(DEFAULT_WAIT)
        listOf(removed, hidden).forEach { runner.attempt(it) }

        listOf(removed, hidden).forEach { postId ->
            assertThat(skipReasonOf(postId)).isEqualTo("GONE")
            assertThat(companions.comments(postId)).isEmpty()
            assertThat(writer.callsOf(postId)).isZero()
        }
    }

    @Test
    fun `US3-AC3 판정이 끝나지 않은 글은 기다렸다가 끝난 뒤 위험이 없을 때만 반응한다`() {
        val author = members.onboarded()
        val safe = postOf(author, "[실패] [위험분류실패:1] 회의가 길었어요", ready = false)
        val risky = postOf(author, "[실패] [위험분류실패:1] [우려] 목록에 없는 표현", ready = false)
        listOf(safe, risky).forEach { postId -> companions.awaitRisk(postId) { it == "PENDING" } }
        awaitRiskAttempts(safe, 1)
        awaitRiskAttempts(risky, 1)

        companions.pass(DEFAULT_WAIT)
        listOf(safe, risky).forEach { assertThat(runner.attempt(it)).isTrue() }

        // 아직 기다린다. 시도로 세지 않고 AI도 부르지 않는다
        listOf(safe, risky).forEach { postId ->
            assertThat(statusOf(postId)).isEqualTo("WAITING")
            assertThat(companions.reaction(postId)!!["attempts"]).isEqualTo(0)
            assertThat(writer.callsOf(postId)).isZero()
            // 다시 볼 때가 되기 전에는 맡지 않는다
            assertThat(runner.attempt(postId)).isFalse()
        }

        // 위험 분류의 재시도가 끝난다
        riskRunner.attempt(com.ogu.post.ContentType.POST, safe)
        riskRunner.attempt(com.ogu.post.ContentType.POST, risky)
        companions.awaitRisk(safe) { it == "DONE" }
        companions.awaitRisk(risky) { it == "DONE" }
        companions.pass(Duration.ofSeconds(30))
        listOf(safe, risky).forEach { assertThat(runner.attempt(it)).isTrue() }

        assertThat(statusOf(safe)).isEqualTo("DONE")
        assertThat(companions.comments(safe)).hasSize(1)
        assertThat(skipReasonOf(risky)).isEqualTo("RISK")
        assertThat(companions.comments(risky)).isEmpty()
    }

    @Test
    fun `US3-AC4 욕설이 든 답은 올라가지 않고 세 번 걸리면 REJECTED로 닫는다`() {
        val postId = postOf(members.onboarded(), "[실패] [AI댓글욕설] 오늘 팀장한테 깨졌어요")

        attemptThreeTimes(postId)

        assertThat(statusOf(postId)).isEqualTo("SKIPPED")
        assertThat(skipReasonOf(postId)).isEqualTo("REJECTED")
        assertThat(companions.reaction(postId)!!["last_error"]).isEqualTo("PROFANITY")
        assertThat(companions.reaction(postId)!!["attempts"]).isEqualTo(3)
        assertThat(writer.callsOf(postId)).isEqualTo(3)
        assertNothingPosted(postId)
    }

    @Test
    fun `US3-AC4 키워드 위험이나 AI 위험 분류에 걸린 답은 올라가지 않는다`() {
        val author = members.onboarded()
        val byAi = postOf(author, "[실패] [AI댓글위험] 다 그만두고 싶어요")
        val byKeyword = postOf(author)
        writer.answers[byKeyword] = CONCERN_TEXT

        companions.pass(DEFAULT_WAIT)
        runner.attempt(byAi)
        runner.attempt(byKeyword)

        assertThat(companions.reaction(byAi)!!["last_error"]).isEqualTo("AI_RISK")
        assertThat(companions.reaction(byKeyword)!!["last_error"]).isEqualTo("KEYWORD_RISK")
        // 키워드에 걸리면 AI 분류까지 가지 않는다
        assertThat(riskClassifier.callsFor("COMPANION:$byKeyword")).isZero()
        assertThat(riskClassifier.callsFor("COMPANION:$byAi")).isEqualTo(1)
        listOf(byAi, byKeyword).forEach { postId ->
            assertThat(statusOf(postId)).isEqualTo("WAITING")
            assertNothingPosted(postId)
        }
    }

    @Test
    fun `US3-AC4 AI 위험 분류가 실패하면 키워드 판정으로 올린다`() {
        val postId = postOf(members.onboarded())
        // 가짜 위험 분류기는 이 표지가 든 글자를 분류하지 못한다
        writer.answers[postId] = "[위험분류실패] 오늘도 정말 애썼어요."

        reactAfter(postId)

        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(companions.comments(postId).single()["content"]).isEqualTo("[위험분류실패] 오늘도 정말 애썼어요.")
    }

    @Test
    fun `US3-AC5 길이나 규칙에 어긋난 답은 올라가지 않고 세 번 걸리면 REJECTED로 닫는다`() {
        val postId = postOf(members.onboarded(), "[실패] [AI댓글긴글] 하소연이 길어요")

        attemptThreeTimes(postId)

        assertThat(skipReasonOf(postId)).isEqualTo("REJECTED")
        assertThat(companions.reaction(postId)!!["last_error"]).isEqualTo("INVALID_RESPONSE")
        assertNothingPosted(postId)
    }

    @Test
    fun `US3-AC6 반응한 뒤 글이 위기로 숨겨지면 다른 회원에게 AI 친구의 댓글도 보이지 않는다`() {
        val author = members.onboarded()
        val other = members.onboarded()
        val postId = postOf(author)
        reactAfter(postId)
        loop.comments(other, postId).andExpect(status().isOk).andExpect(jsonPath("$.data.items.length()").value(1))

        loop.updatePost(author, postId, mapOf("content" to "[실패] $CRISIS_TEXT")).andExpect(status().isNoContent)

        assertThat(safety.postState(postId)).containsEntry("hidden", true)
        loop.detail(other, postId).andExpect(status().isNotFound)
        loop.comments(other, postId).andExpect(status().isNotFound)
    }

    @Test
    fun `US3-AC7 AI 친구의 댓글도 신고되고 운영자가 숨길 수 있다`() {
        val author = members.onboarded()
        val reporter = members.onboarded()
        val operator = members.onboarded().also(safety::grantOperator)
        val postId = postOf(author)
        reactAfter(postId)
        val commentId = companions.comments(postId).single()["id"] as Long

        val report = mapOf("targetType" to "COMMENT", "targetId" to commentId, "reason" to "OTHER", "detail" to "불편해요")
        safety.send(reporter, HttpMethod.POST, "/api/v1/reports", report).andExpect(status().isNoContent)
        safety
            .send(operator, HttpMethod.PUT, "/api/v1/operator/contents/COMMENT/$commentId/hidden")
            .andExpect(status().isNoContent)

        assertThat(companions.comments(postId).single()["hidden"]).isEqualTo(true)
        loop
            .comments(reporter, postId)
            .andExpect(jsonPath("$.data.items[0].hidden").value(true))
            .andExpect(jsonPath("$.data.items[0].content").isEmpty)
            .andExpect(jsonPath("$.data.items[0].author").isEmpty)
        // 숨긴 뒤에도 AI 친구는 다시 남기지 않는다
        assertThat(reactAfter(postId, Duration.ofHours(1))).isFalse()
        assertThat(companions.comments(postId)).hasSize(1)
    }

    @Test
    fun `FR-013 AI가 세 번 실패하면 FAILED로 닫고 글쓰기와 사람의 댓글은 그대로 된다`() {
        val author = members.onboarded()
        val postId = postOf(author, "[실패] [AI댓글실패] 답이 오지 않는 글")

        attemptThreeTimes(postId)

        assertThat(skipReasonOf(postId)).isEqualTo("FAILED")
        assertThat(companions.reaction(postId)!!["last_error"]).isEqualTo("UPSTREAM_ERROR")
        assertNothingPosted(postId)
        // 회원에게는 아무 오류도 보이지 않는다
        loop.detail(author, postId).andExpect(status().isOk)
        loop.comment(members.onboarded(), postId)
        loop.likePost(members.onboarded(), postId).andExpect(status().isOk)
    }

    @Test
    fun `FR-013 두 번 실패한 뒤 답이 오면 반응한다`() {
        val postId = postOf(members.onboarded(), "[실패] [AI댓글실패:2] 늦게 답이 오는 글")

        attemptThreeTimes(postId)

        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(companions.reaction(postId)!!["attempts"]).isEqualTo(3)
        assertThat(companions.comments(postId)).hasSize(1)
    }

    @Test
    fun `FR-020 AI 친구의 반응은 로그와 기록과 이벤트에 본문과 댓글을 남기지 않는다`(output: CapturedOutput) {
        val author = members.onboarded()
        val posted = postOf(author, "[실패] $BODY_MARKER 오늘 있었던 일")
        val rejected = postOf(author, "[실패] [AI댓글욕설] $BODY_MARKER 또 다른 일")
        writer.answers[posted] = "$ANSWER_MARKER 오늘도 애썼어요."

        companions.pass(DEFAULT_WAIT)
        runner.attempt(posted)
        runner.attempt(rejected)
        safety.awaitListenersIdle()

        assertThat(statusOf(posted)).isEqualTo("DONE")
        assertThat(output.all).doesNotContain(BODY_MARKER, ANSWER_MARKER, FakeCompanionCommentWriter.PROFANE_COMMENT)
        val rows = listOf(posted, rejected).map { companions.reaction(it).toString() }
        assertThat(rows).noneMatch { BODY_MARKER in it || ANSWER_MARKER in it || "지랄" in it }
        val events =
            jdbcTemplate.queryForList(
                "select serialized_event from event_publication " +
                    "where serialized_event like ? or serialized_event like ?",
                String::class.java,
                "%$BODY_MARKER%",
                "%$ANSWER_MARKER%",
            )
        assertThat(events).isEmpty()
    }

    private fun attemptThreeTimes(postId: Long) {
        // 기다리는 시간 뒤, 그리고 재시도 간격(1분, 2분) 뒤
        listOf(DEFAULT_WAIT, Duration.ofMinutes(1), Duration.ofMinutes(2)).forEach { reactAfter(postId, it) }
    }

    private fun assertNothingPosted(postId: Long) {
        assertThat(companions.comments(postId)).isEmpty()
        assertThat(companions.likers(postId)).isEmpty()
    }

    private fun awaitRiskAttempts(
        postId: Long,
        attempts: Int,
    ) {
        org.awaitility.Awaitility
            .await()
            .atMost(com.ogu.support.CompanionFixture.AWAIT_LIMIT)
            .until {
                jdbcTemplate.queryForObject(
                    "select attempts from risk_assessment where target_type = 'POST' and target_id = ? " +
                        "order by id desc limit 1",
                    Int::class.java,
                    postId,
                ) == attempts
            }
    }

    private companion object {
        val DEFAULT_WAIT: Duration = Duration.ofMinutes(10)
        const val BODY_MARKER = "BODY-MARKER-3f9a"
        const val ANSWER_MARKER = "ANSWER-MARKER-c41d"
    }
}
