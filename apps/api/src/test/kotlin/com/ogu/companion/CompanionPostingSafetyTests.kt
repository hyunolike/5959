package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.infrastructure.FakeCompanionPostWriter
import com.ogu.companion.application.PostTopics
import com.ogu.post.CommentTone
import com.ogu.post.PostApi
import com.ogu.post.PostReportApi
import com.ogu.support.CompanionFixture
import com.ogu.support.CompanionSettings
import com.ogu.support.CompanionTestConfiguration
import com.ogu.support.FutureClockSchedulers
import com.ogu.support.SafetyFixture.Companion.CONCERN_TEXT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Date
import java.sql.Timestamp

/**
 * AI 친구의 글도 안전 규칙을 그대로 지킨다(012 US5, research R15, R16). AI의 답은 가짜 글쓰기에 정해 둔 답이나 실패로
 * 만든다. 정해 둔 답은 모양 검사를 거치지 않고 그대로 나와, 올리기 전 검사(욕설, 키워드 위험, AI 위험 분류)만 받는다.
 */
@SpringBootTest(
    properties = [
        CompanionSettings.ENABLED,
        CompanionSettings.NO_SCHEDULER,
        CompanionSettings.LONG_TOKEN,
        CompanionSettings.NO_LIMIT,
        CompanionSettings.POSTING,
        CompanionSettings.NO_POSTING_SCHEDULER,
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
class CompanionPostingSafetyTests : CompanionPostingTestBase() {
    @Autowired
    lateinit var postApi: PostApi

    @Autowired
    lateinit var reportApi: PostReportApi

    @Test
    fun `US5-AC1 욕설이 든 글은 올라가지 않고 세 번 걸리면 REJECTED로 그만둔다`() {
        postWriter.answer = FakeCompanionPostWriter.PROFANE_POST

        attemptThreeTimes()

        assertGivenUp("REJECTED", "PROFANITY")
    }

    @Test
    fun `US5-AC1 키워드 위험이나 AI 위험 분류에 걸린 글은 올라가지 않는다`() {
        postWriter.answer = "[실패] $CONCERN_TEXT 오늘도 회의가 길었어요."
        attemptThreeTimes()
        assertGivenUp("REJECTED", "KEYWORD_RISK")

        newQuietDay()
        postWriter.answer = FakeCompanionPostWriter.RISKY_POST
        attemptThreeTimes()
        assertGivenUp("REJECTED", "AI_RISK")
    }

    @Test
    fun `US5-AC2 모양이나 규칙에 어긋난 글은 올라가지 않는다`() {
        postWriter.failure = EmotionAnalysisFailed.Kind.INVALID_RESPONSE

        attemptThreeTimes()

        assertGivenUp("REJECTED", "INVALID_RESPONSE")
    }

    @Test
    fun `US5-AC2 글의 길이 규칙을 넘는 글은 저장되지 않는다`() {
        // 검증기를 지나친 긴 글도 글의 규칙(500자)이 막는다
        postWriter.answer = "[실패] " + "가".repeat(500)

        attemptThreeTimes()

        assertGivenUp("REJECTED", "POST_INVALID_REQUEST")
    }

    @Test
    fun `US5-AC3 올리기 전의 AI 위험 분류가 실패하면 키워드 판정만으로 올리지 않고 다시 시도한다`() {
        postWriter.answer = "[실패] [위험분류실패] 오늘 회의가 길었어요. 준비한 말은 꺼내지도 못했어요."

        assertThat(postingRunner.runOnce()).isTrue()

        // 올리지 않고 기다린다
        val waiting = records().single()
        assertThat(waiting["status"]).isEqualTo("WRITING")
        assertThat(waiting["last_error"]).isEqualTo("RISK_UNCLASSIFIED")
        assertThat(aiPosts()).isEmpty()

        // 분류가 되는 답이 오면 올린다
        postWriter.answer = null
        passRetry()
        assertThat(postingRunner.runOnce()).isTrue()

        assertThat(records().single()["status"]).isEqualTo("POSTED")
        assertThat(records().single()["attempts"]).isEqualTo(2)
        assertThat(aiPosts()).hasSize(1)
    }

    @Test
    fun `US5-AC3 분류가 세 번 실패하면 FAILED로 그만두고 글은 올라가지 않는다`() {
        postWriter.answer = "[실패] [위험분류실패] 오늘 회의가 길었어요. 준비한 말은 꺼내지도 못했어요."

        attemptThreeTimes()

        assertGivenUp("FAILED", "RISK_UNCLASSIFIED")
    }

    @Test
    fun `US5-AC4 AI에 보내는 것은 소재와 말씨와 댓글 말투뿐이다`() {
        // 회원의 글이 있어도 재료가 되지 않는다
        val author = members.onboarded()
        postOf(author, "[실패] MEMBER-BODY-7c1e 우리 팀 이야기예요")
        companions.pass(QUIET)

        postingRunner.runOnce()

        val input = postWriter.inputs.single()
        assertThat(PostTopics.ALL.map { it.description }).contains(input.topic)
        assertThat(input.tone.name).isIn(CommentTone.entries.map { it.name })
        assertThat(input.voice.name).isIn("GENTLE", "HEARTY", "CALM")
        assertThat(input.toString()).doesNotContain("MEMBER-BODY-7c1e", author.id.toString())
    }

    @Test
    fun `US5-AC5 올라간 뒤 숨겨진 AI 친구의 글은 다른 회원에게 보이지 않는다`() {
        postingRunner.runOnce()
        val postId = aiPosts().single()["id"] as Long
        val member = members.onboarded()
        loop.detail(member, postId).andExpect(status().isOk)

        // 올라간 뒤의 분류가 위기로 판정하면 사람의 글과 같은 길로 숨겨진다
        jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'RISK' where id = ?", postId)

        loop.detail(member, postId).andExpect(status().isNotFound)
    }

    @Test
    fun `US5-AC6 AI 친구의 글도 신고할 수 있고 운영자가 숨길 수 있다`() {
        postingRunner.runOnce()
        val postId = aiPosts().single()["id"] as Long
        val reporter = members.onboarded()
        val operator = members.onboarded().also(safety::grantOperator)

        val report = mapOf("targetType" to "POST", "targetId" to postId, "reason" to "OTHER", "detail" to "불편해요")
        safety.send(reporter, HttpMethod.POST, "/api/v1/reports", report).andExpect(status().isNoContent)
        safety
            .send(operator, HttpMethod.PUT, "/api/v1/operator/contents/POST/$postId/hidden")
            .andExpect(status().isNoContent)

        loop.detail(reporter, postId).andExpect(status().isNotFound)
        // 숨겨진 글은 조용한 시간을 잴 때 없는 글이다. 그래도 그 친구는 오늘 다시 쓰지 않는다
        assertThat(postingRunner.runOnce()).isTrue()
        assertThat(records().map { it["companion_id"] }).doesNotHaveDuplicates().hasSize(2)
    }

    @Test
    fun `US5-AC7 AI가 세 번 답하지 못하면 그만두고 회원의 글쓰기와 읽기는 그대로 된다`() {
        postWriter.failure = EmotionAnalysisFailed.Kind.UPSTREAM_ERROR

        attemptThreeTimes()

        assertGivenUp("FAILED", "UPSTREAM_ERROR")
        assertThat(postWriter.calls).isEqualTo(3)
        // 넷째는 청하지 않는다
        passRetry()
        postingRunner.runOnce()
        assertThat(postWriter.calls).isLessThanOrEqualTo(4)
        val author = members.onboarded()
        val postId = postOf(author)
        loop.detail(author, postId).andExpect(status().isOk)
    }

    @Test
    fun `US5-AC7 회로가 열려 호출이 나가지 않으면 시도로 세지 않는다`() {
        postWriter.failure = EmotionAnalysisFailed.Kind.CIRCUIT_OPEN

        repeat(4) {
            postingRunner.runOnce()
            passRetry()
        }

        val record = records().single()
        assertThat(record["status"]).isEqualTo("WRITING")
        assertThat(record["attempts"]).isEqualTo(0)
        assertThat(record["last_error"]).isEqualTo("CIRCUIT_OPEN")
    }

    @Test
    fun `US3-AC10 글을 올린 뒤 기록을 적기 전에 멈췄다 다시 돌면 그 글로 닫고 글이 늘지 않는다`() {
        // 쓰는 중인 기록이 있고, 그 친구의 글은 이미 저장된 상태
        val friend = companions.memberIdByCompanion.entries.first()
        val now = Timestamp.from(clock.instant())
        jdbcTemplate.update(
            "insert into companion_post " +
                "(companion_id, day, topic, comment_tone, status, next_attempt_at, created_at) " +
                "values (?, ?, 'MEETING_SILENCE', 'COMFORT_ME', 'WRITING', ?, ?)",
            friend.key,
            Date.valueOf(clock.instant().atZone(CompanionFixture.SEOUL).toLocalDate()),
            now,
            now,
        )
        val saved = companionPostApi.write(friend.value, "[실패] 이미 저장된 글이에요. 회의가 길었어요.", CommentTone.COMFORT_ME)

        assertThat(postingRunner.runOnce()).isTrue()

        val record = records().single()
        assertThat(record["status"]).isEqualTo("POSTED")
        assertThat(record["post_id"]).isEqualTo(saved)
        assertThat(aiPosts().map { it["id"] }).containsExactly(saved)
        assertThat(postWriter.calls).isZero()
    }

    @Test
    fun `FR-020 사람 회원의 이름으로는 AI 친구의 길로 글을 쓰지 못한다`() {
        val human = members.onboarded()

        assertThatThrownBy { companionPostApi.write(human.id, "[실패] 직군 없이 쓰려는 글", CommentTone.COMFORT_ME) }
            .isInstanceOf(IllegalArgumentException::class.java)

        val written = "select count(*) from posts where author_id = ?"
        assertThat(jdbcTemplate.queryForObject(written, Int::class.java, human.id)).isZero()
    }

    @Test
    fun `FR-034 회원은 AI 친구의 글을 고치거나 지울 수 없다`() {
        postingRunner.runOnce()
        val postId = aiPosts().single()["id"] as Long
        val member = members.onboarded()

        loop.updatePost(member, postId, mapOf("content" to "남의 글을 고치려는 글")).andExpect(status().isForbidden)
        loop.removePost(member, postId).andExpect(status().isForbidden)

        assertThat(aiPosts().single()["deleted_at"]).isNull()
    }

    @Test
    fun `US5-AC8 AI 친구는 주간 리포트의 대상이 아니고 레이드 보스의 감정을 정하는 글에 들지 않는다`() {
        val human = members.onboarded()
        val humanPost = postOf(human)
        companions.pass(QUIET)
        postingRunner.runOnce()
        val aiPost = aiPosts().single()

        val from = started.minusSeconds(1)
        val until = clock.instant().plusSeconds(1)
        val authors = reportApi.authorIdsBetween(from, until, afterAuthorId = 0, limit = 10_000)
        val raidPosts = postApi.visibleIdsSince(from, limit = 10_000)

        assertThat(authors).contains(human.id).doesNotContain(aiPost["author_id"] as Long)
        assertThat(raidPosts).contains(humanPost).doesNotContain(aiPost["id"] as Long)
    }

    @Test
    fun `FR-037 AI 친구의 글쓰기는 로그와 기록에 본문을 남기지 않는다`(output: CapturedOutput) {
        postWriter.answer = "[실패] $BODY_MARKER 오늘 회의가 길었어요."
        postingRunner.runOnce()
        newQuietDay()
        postWriter.answer = FakeCompanionPostWriter.PROFANE_POST
        attemptThreeTimes()

        assertThat(aiPosts().map { it["content"] as String }).anyMatch { BODY_MARKER in it }
        assertThat(output.all).doesNotContain(BODY_MARKER, FakeCompanionPostWriter.PROFANE_POST)
        val recorded = jdbcTemplate.queryForList("select * from companion_post").joinToString { it.values.toString() }
        assertThat(recorded).doesNotContain(BODY_MARKER)
    }

    /** 다른 날의 조용한 아침으로 옮긴다. 이 테스트가 만든 기록과 글은 계속 본다. */
    private fun newQuietDay() {
        leaveNothingWriting()
        companions.quietDay()
    }

    /** 실패한 시도 뒤 다시 맡을 수 있게 시간을 보내며 세 번 돈다. */
    private fun attemptThreeTimes() {
        repeat(3) {
            assertThat(postingRunner.runOnce()).isTrue()
            passRetry()
        }
    }

    /** 가장 최근 기록이 [reason]으로 그만뒀고, 그 기록의 글은 올라가지 않았다. */
    private fun assertGivenUp(
        reason: String,
        lastError: String,
    ) {
        val record = records().last()
        assertThat(record["status"]).isEqualTo("GIVEN_UP")
        assertThat(record["give_up_reason"]).isEqualTo(reason)
        assertThat(record["last_error"]).isEqualTo(lastError)
        assertThat(record["attempts"]).isEqualTo(3)
        assertThat(record["post_id"]).isNull()
        val opened = (record["created_at"] as Timestamp).toInstant()
        assertThat(companions.aiPosts(opened)).isEmpty()
    }

    private companion object {
        const val BODY_MARKER = "POST-BODY-MARKER-5b2d"
    }
}
