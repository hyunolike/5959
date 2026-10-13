package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.infrastructure.FakeCompanionCommentWriter
import com.ogu.post.ContentType
import com.ogu.safety.application.RiskClassificationRunner
import com.ogu.support.CompanionFixture
import com.ogu.support.CompanionSettings
import com.ogu.support.CompanionTestConfiguration
import com.ogu.support.FutureClockSchedulers
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration

/**
 * 사람이 답하면 남은 AI 친구는 물러난다(012 US2). 수가 3으로 정해진 글에 첫 친구가 반응한 뒤 멈출 까닭을 만들고, 남은
 * 친구가 반응하지 않는지와 이미 남긴 댓글과 공감이 그대로인지 본다.
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
class CompanionFollowUpStopTests : CompanionTestBase() {
    @Autowired
    lateinit var riskRunner: RiskClassificationRunner

    @Test
    fun `US2-AC1 다음 친구가 반응하기 전에 사람이 댓글을 달면 남은 친구는 반응하지 않는다`() {
        val postId = firstReacted(members.onboarded())
        loop.comment(members.onboarded(), postId, "저도 같은 날이 있었어요")

        assertThat(followUp(postId)).isTrue()

        assertStopped(postId, "HUMAN_COMMENT")
        // 더 지나도 다시 열리지 않는다
        assertThat(followUp(postId)).isFalse()
    }

    @Test
    fun `US2-AC1 사람이 달았다가 지운 댓글 때문에 멈춘 반응은 다시 시작하지 않는다`() {
        val postId = firstReacted(members.onboarded())
        val human = members.onboarded()
        val commentId = loop.comment(human, postId, "잠깐 남겼다 지울 댓글이에요")
        followUp(postId)
        loop.removeComment(human, commentId).andExpect(status().isNoContent)

        assertThat(followUp(postId)).isFalse()

        assertStopped(postId, "HUMAN_COMMENT")
    }

    @Test
    fun `US2-AC2 글이 지워지거나 숨겨지면 남은 친구는 반응하지 않는다`() {
        val author = members.onboarded()
        val removed = firstReacted(author)
        val hidden = firstReacted(author)
        loop.removePost(author, removed).andExpect(status().isNoContent)
        jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'OPERATOR' where id = ?", hidden)

        companions.pass(CompanionFixture.FOLLOW_UP)
        listOf(removed, hidden).forEach { assertThat(runner.attempt(it)).isTrue() }

        listOf(removed, hidden).forEach { postId ->
            val row = companions.reaction(postId)!!
            assertThat(row["status"]).isEqualTo("DONE")
            assertThat(row["skip_reason"]).isEqualTo("GONE")
            assertThat(countsOf(postId)).isEqualTo(3 to 1)
            assertThat(writer.callsOf(postId)).isEqualTo(1)
        }
    }

    @Test
    fun `US2-AC3 첫 반응 뒤 글이 고쳐져 우려로 판정되면 남은 친구는 반응하지 않는다`() {
        val author = members.onboarded()
        val postId = firstReacted(author)
        loop.updatePost(author, postId, mapOf("content" to "[실패] [우려] 고친 뒤의 글이에요")).andExpect(status().isNoContent)
        companions.awaitRisk(postId) { it == "DONE" }

        assertThat(followUp(postId)).isTrue()

        assertStopped(postId, "RISK")
    }

    @Test
    fun `US2-AC4 글이 고쳐져 판정이 다시 열렸으면 끝날 때까지 기다리고 위험이 없을 때만 반응한다`() {
        val author = members.onboarded()
        val postId = firstReacted(author)
        loop
            .updatePost(author, postId, mapOf("content" to "[실패] [위험분류실패:1] 고친 뒤의 글이에요"))
            .andExpect(status().isNoContent)
        companions.awaitRisk(postId) { it == "PENDING" }
        awaitRiskAttempts(postId, 1)

        assertThat(followUp(postId)).isTrue()

        // 아직 기다린다. 시도로 세지 않고 AI도 부르지 않는다
        assertThat(statusOf(postId)).isEqualTo("WAITING")
        assertThat(companions.reaction(postId)!!["attempts"]).isEqualTo(0)
        assertThat(writer.callsOf(postId)).isEqualTo(1)

        riskRunner.attempt(ContentType.POST, postId)
        companions.awaitRisk(postId) { it == "DONE" }
        assertThat(reactAfter(postId, Duration.ofSeconds(30))).isTrue()

        assertThat(companions.comments(postId)).hasSize(2)
        assertThat(countsOf(postId)).isEqualTo(3 to 2)
    }

    @Test
    fun `US2-AC5 다음 차례가 오기 전에 24시간이 지나면 남은 친구는 반응하지 않는다`() {
        val postId = firstReacted(members.onboarded())

        assertThat(reactAfter(postId, Duration.ofHours(24))).isTrue()

        assertStopped(postId, "EXPIRED")
    }

    @Test
    fun `US2-AC6 하루의 몫이 떨어지면 남은 친구는 반응하지 않고 다음 날에도 다시 보지 않는다`() {
        companions.freshDay()
        try {
            // 첫 친구가 마지막 몫을 쓴다
            companions.spendToday(CompanionFixture.LIMIT - 1)
            val postId = firstReacted(members.onboarded())

            assertThat(followUp(postId)).isTrue()

            assertStopped(postId, "DAILY_LIMIT")
            assertThat(companions.usedToday()).isEqualTo(CompanionFixture.LIMIT)
            companions.freshDay()
            assertThat(runner.attempt(postId)).isFalse()
            assertThat(companions.comments(postId)).hasSize(1)
        } finally {
            companions.freshDay()
        }
    }

    @Test
    fun `US2-AC7 뒤따르는 친구가 세 번 답하지 못하면 남은 반응도 그만둔다`() {
        val postId = firstReacted(members.onboarded())
        writer.failures[postId] = EmotionAnalysisFailed.Kind.UPSTREAM_ERROR

        repeat(3) { assertThat(followUp(postId)).isTrue() }

        assertStopped(postId, "FAILED", calls = 4)
        assertThat(companions.reaction(postId)!!["attempts"]).isEqualTo(3)
        // 셋째 친구에게는 차례가 오지 않는다
        writer.failures.remove(postId)
        assertThat(followUp(postId)).isFalse()
    }

    @Test
    fun `US2-AC7 뒤따르는 친구의 시도 횟수는 앞 친구의 것과 따로 센다`() {
        // 첫 친구가 두 번 실패하고 세 번째에 남긴다
        val postId = postOf(members.onboarded(), "[실패] [AI댓글실패:2] 늦게 답이 오는 글", count = 2)
        listOf(CompanionFixture.WAIT, Duration.ofMinutes(1), Duration.ofMinutes(2)).forEach { reactAfter(postId, it) }
        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(companions.reaction(postId)!!["attempts"]).isEqualTo(0)

        assertThat(followUp(postId)).isTrue()

        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(skipReasonOf(postId)).isNull()
        assertThat(companions.comments(postId)).hasSize(2)
    }

    @Test
    fun `US2-AC8 뒤따르는 친구의 댓글도 욕설 검사와 위험 검사를 거치고 걸리면 올라가지 않는다`() {
        val profane = firstReacted(members.onboarded())
        val risky = firstReacted(members.onboarded())
        writer.answers[profane] = FakeCompanionCommentWriter.PROFANE_COMMENT
        writer.answers[risky] = FakeCompanionCommentWriter.RISKY_COMMENT

        repeat(3) { listOf(profane, risky).forEach { followUp(it) } }

        assertStopped(profane, "REJECTED", calls = 4)
        assertStopped(risky, "REJECTED", calls = 4)
        assertThat(companions.reaction(profane)!!["last_error"]).isEqualTo("PROFANITY")
        assertThat(companions.reaction(risky)!!["last_error"]).isEqualTo("AI_RISK")
    }

    /** 수가 3으로 정해진 글에 첫 AI 친구가 반응한 상태를 만든다. */
    private fun firstReacted(author: TestMember): Long {
        val postId = postOf(author, count = 3)
        assertThat(reactAfter(postId)).isTrue()
        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(statusOf(postId)).isEqualTo("WAITING")
        return postId
    }

    /** 첫 친구의 댓글과 공감만 남은 채 [reason]으로 멈췄다. 한 명은 반응했으므로 기록은 `DONE`이다. */
    private fun assertStopped(
        postId: Long,
        reason: String,
        calls: Int = 1,
    ) {
        val row = companions.reaction(postId)!!
        assertThat(row["status"]).isEqualTo("DONE")
        assertThat(row["skip_reason"]).isEqualTo(reason)
        assertThat(row["next_attempt_at"]).isNull()
        assertThat(countsOf(postId)).isEqualTo(3 to 1)
        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(companions.likers(postId)).hasSize(1)
        assertThat(companions.entries(postId)).hasSize(1)
        assertThat(writer.callsOf(postId)).isEqualTo(calls)
    }

    private fun awaitRiskAttempts(
        postId: Long,
        attempts: Int,
    ) {
        await().atMost(CompanionFixture.AWAIT_LIMIT).until {
            jdbcTemplate.queryForObject(
                "select attempts from risk_assessment where target_type = 'POST' and target_id = ? " +
                    "order by id desc limit 1",
                Int::class.java,
                postId,
            ) == attempts
        }
    }
}
