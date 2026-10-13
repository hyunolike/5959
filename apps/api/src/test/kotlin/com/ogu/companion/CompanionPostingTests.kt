package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.infrastructure.CompanionPostValidator
import com.ogu.companion.application.PostTopics
import com.ogu.shared.text.Grapheme
import com.ogu.support.CompanionSettings
import com.ogu.support.CompanionTestConfiguration
import com.ogu.support.FutureClockSchedulers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 조용한 날에는 AI 친구가 먼저 이야기를 꺼낸다(012 US3, US7의 글쓰기 쪽). */
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
class CompanionPostingTests : CompanionPostingTestBase() {
    @Test
    fun `US3-AC1 6시간 동안 보이는 새 글이 없으면 AI 친구 한 명이 고민 글 하나를 쓴다`() {
        assertThat(postingRunner.runOnce()).isTrue()

        val record = records().single()
        val post = aiPosts().single()
        assertThat(record["status"]).isEqualTo("POSTED")
        assertThat(record["post_id"]).isEqualTo(post["id"])
        assertThat(record["attempts"]).isEqualTo(1)
        assertThat(record["completed_at"]).isNotNull()
        assertThat(post["author_id"]).isEqualTo(memberIdOf(record))
        // 직군과 경력을 꾸며 붙이지 않는다
        assertThat(post["author_kind"]).isEqualTo("AI")
        assertThat(post["author_job_role"]).isNull()
        assertThat(post["author_career_year"]).isNull()
        assertThat(postWriter.calls).isEqualTo(1)
        // 방금 글이 올라왔으니 더 쓰지 않는다
        assertThat(postingRunner.runOnce()).isFalse()
        assertThat(aiPosts()).hasSize(1)
    }

    @Test
    fun `US3-AC2 6시간 안에 사람이 쓴 보이는 글이 있으면 쓰지 않는다`() {
        postOf(members.onboarded())

        companions.pass(QUIET.minusMinutes(1))
        assertThat(postingRunner.runOnce()).isFalse()
        assertThat(records()).isEmpty()
        assertThat(postWriter.calls).isZero()

        companions.pass(Duration.ofMinutes(1))
        assertThat(postingRunner.runOnce()).isTrue()
        assertThat(aiPosts()).hasSize(1)
    }

    @Test
    fun `US3-AC2 6시간 안에 AI 친구가 쓴 글이 있어도 쓰지 않는다`() {
        postingRunner.runOnce()

        companions.pass(QUIET.minusMinutes(1))
        assertThat(postingRunner.runOnce()).isFalse()
        assertThat(aiPosts()).hasSize(1)

        companions.pass(Duration.ofMinutes(1))
        assertThat(postingRunner.runOnce()).isTrue()
        assertThat(aiPosts()).hasSize(2)
    }

    @Test
    fun `US3-AC2 지웠거나 숨긴 글은 새 글로 치지 않는다`() {
        val author = members.onboarded()
        val removed = postOf(author)
        val hidden = postOf(author)
        loop.removePost(author, removed).andExpect(status().isNoContent)
        jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'OPERATOR' where id = ?", hidden)

        assertThat(postingRunner.runOnce()).isTrue()

        assertThat(aiPosts()).hasSize(1)
    }

    @Test
    fun `US3-AC3 그날 올린 글이 하루의 수에 닿으면 조용해도 더 쓰지 않는다`() {
        val runner = eagerRunner { it.copy(dailyPosts = 2) }

        val wrote =
            (1..3).map {
                runner.runOnce().also { passEagerQuiet() }
            }

        assertThat(wrote).containsExactly(true, true, false)
        assertThat(aiPosts()).hasSize(2)
        assertThat(records()).hasSize(2)
    }

    @Test
    fun `US3-AC4 오늘 쓴 친구는 뽑히지 않고 글을 가장 오래 쉰 친구부터 차례로 쓴다`() {
        val runner = eagerRunner { it.copy(dailyPosts = 10) }
        val expected = postingRestOrder()

        val wrote =
            (1..4).map {
                runner.runOnce().also { passEagerQuiet() }
            }

        // 친구는 셋이고 하루에 한 번씩이라 넷째는 쓸 친구가 없다
        assertThat(wrote).containsExactly(true, true, true, false)
        assertThat(records().map { (it["companion_id"] as Number).toInt() }).isEqualTo(expected)
        assertThat(aiPosts().map { it["author_id"] }).doesNotHaveDuplicates().hasSize(3)
    }

    @Test
    fun `US3-AC5 정해 둔 시간대 밖에는 조용해도 쓰지 않는다`() {
        // 8시 전
        companions.quietDay(hour = 7)
        started = clock.instant()
        companions.pass(Duration.ofMinutes(59))
        assertThat(postingRunner.runOnce()).isFalse()
        companions.pass(Duration.ofMinutes(1))
        assertThat(postingRunner.runOnce()).isTrue()

        // 23시부터
        companions.quietDay(hour = 23)
        assertThat(postingRunner.runOnce()).isFalse()
        assertThat(aiPosts()).hasSize(1)
    }

    @Test
    fun `US3-AC6 소재는 정해 둔 목록에서 고르고 최근 열 개가 쓴 소재는 고르지 않는다`() {
        val runner = eagerRunner()

        // 사흘 동안 아홉 개를 쓴다
        repeat(3) { day ->
            if (day > 0) companions.quietDay()
            repeat(3) {
                assertThat(runner.runOnce()).isTrue()
                passEagerQuiet()
            }
        }

        val topics = records().map { it["topic"] as String }
        assertThat(topics).hasSize(9).doesNotHaveDuplicates()
        assertThat(topics).allSatisfy { assertThat(PostTopics.find(it)).isNotNull() }
        // AI에는 그 소재의 설명이 갔다
        assertThat(postWriter.inputs.map { it.topic }).isEqualTo(topics.map { PostTopics.find(it)!!.description })
    }

    @Test
    fun `US3-AC7 글은 500자 안이고 댓글 말투 네 가지 가운데 하나가 골라져 있다`() {
        postingRunner.runOnce()

        val post = aiPosts().single()
        val record = records().single()
        assertThat(Grapheme.count(post["content"] as String)).isBetween(1, CompanionPostValidator.MAX_LENGTH)
        assertThat(post["comment_tone"]).isIn("VENT_WITH_ME", "COMFORT_ME", "WARM_ADVICE", "MAKE_ME_LAUGH")
        assertThat(post["comment_tone"]).isEqualTo(record["comment_tone"])
        assertThat(
            postWriter.inputs
                .single()
                .tone.name,
        ).isEqualTo(record["comment_tone"])
    }

    @Test
    fun `US3-AC8 AI 친구의 글도 감정이 분석되어 몬스터가 생기고 위험 판정과 반응 기록을 받는다`() {
        postWriter.answer = "[불안:낮음] 회의에서 준비한 말을 끝내 꺼내지 못했어요. 다들 바빠 보여서 손을 들 틈이 없었어요."

        postingRunner.runOnce()

        val postId = aiPosts().single()["id"] as Long
        assertThat(loop.awaitMonster(postId).hp).isPositive()
        companions.awaitReady(postId)
        assertThat(statusOf(postId)).isEqualTo("WAITING")
    }

    @Test
    fun `US3-AC9 회원의 공감과 댓글이 AI 친구의 글의 몬스터 HP를 줄인다`() {
        postWriter.answer = "[불안:낮음] 퇴근 십 분 전에 내일 아침까지 해 달라는 일을 받았어요. 저녁 약속은 그대로 미뤘어요."
        postingRunner.runOnce()
        val postId = aiPosts().single()["id"] as Long
        val monster = loop.awaitMonster(postId)
        val member = members.onboarded()

        loop.likePost(member, postId).andExpect(status().isOk)
        loop.comment(member, postId, "그건 정말 너무했네요")

        assertThat(loop.monster(postId)!!.hp).isEqualTo(monster.maxHp - 4)
        assertThat(loop.hpLogs(postId).map { it.memberId }).containsOnly(member.id)
    }

    @Test
    fun `US3-AC10 여러 실행기가 함께 돌아도 글은 하나만 올라온다`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENCY)

        val wrote =
            try {
                (1..CONCURRENCY)
                    .map {
                        executor.submit(
                            Callable {
                                start.await(5, TimeUnit.SECONDS)
                                postingRunner.runOnce()
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertThat(wrote.count { it }).isEqualTo(1)
        assertThat(records()).hasSize(1)
        assertThat(aiPosts()).hasSize(1)
        assertThat(postWriter.calls).isEqualTo(1)
    }

    @Test
    fun `US7-AC1 글쓰기만 꺼도 반응은 그대로 하고 이미 올라간 AI 친구의 글은 남는다`() {
        postingRunner.runOnce()
        val off = runnerWith { it.copy(enabled = false, quiet = EAGER_QUIET) }
        passEagerQuiet()

        assertThat(off.runOnce()).isFalse()

        assertThat(aiPosts()).hasSize(1)
        // 반응은 009의 스위치를 따른다
        val postId = postOf(members.onboarded())
        assertThat(reactAfter(postId)).isTrue()
        assertThat(companions.comments(postId)).hasSize(1)
    }

    @Test
    fun `US7-AC5 기록에 누가 언제 어떤 소재로 썼는지와 그만둔 까닭이 남고 본문은 없다`() {
        postingRunner.runOnce()
        postWriter.failure = EmotionAnalysisFailed.Kind.UPSTREAM_ERROR
        companions.pass(QUIET)
        repeat(3) {
            postingRunner.runOnce()
            passRetry()
        }

        val (posted, givenUp) = records()
        assertThat(posted.keys).containsExactlyInAnyOrder(
            "id",
            "companion_id",
            "day",
            "topic",
            "comment_tone",
            "status",
            "give_up_reason",
            "post_id",
            "attempts",
            "next_attempt_at",
            "last_error",
            "created_at",
            "completed_at",
        )
        assertThat(posted["give_up_reason"]).isNull()
        assertThat(givenUp["status"]).isEqualTo("GIVEN_UP")
        assertThat(givenUp["give_up_reason"]).isEqualTo("FAILED")
        assertThat(givenUp["last_error"]).isEqualTo("UPSTREAM_ERROR")
        assertThat(givenUp["attempts"]).isEqualTo(3)
        assertThat(givenUp["post_id"]).isNull()
        assertThat(givenUp["companion_id"]).isNotEqualTo(posted["companion_id"])
    }

    @Test
    fun `US7-AC6 그날 AI에 글을 청한 횟수가 상한에 닿으면 더 청하지 않는다`() {
        val runner = runnerWith { it.copy(dailyRequests = 1) }
        postWriter.failure = EmotionAnalysisFailed.Kind.UPSTREAM_ERROR

        assertThat(runner.runOnce()).isTrue()
        passRetry()
        assertThat(runner.runOnce()).isTrue()

        val record = records().single()
        assertThat(record["status"]).isEqualTo("GIVEN_UP")
        assertThat(record["give_up_reason"]).isEqualTo("DAILY_LIMIT")
        assertThat(postWriter.calls).isEqualTo(1)
        // 그날은 새 기록도 열지 않는다
        passRetry()
        assertThat(runner.runOnce()).isFalse()
        assertThat(records()).hasSize(1)
    }

    @Test
    fun `US3-AC2 쓰는 사이에 새 글이 올라왔으면 다시 시도하지 않고 그만둔다`() {
        postWriter.failure = EmotionAnalysisFailed.Kind.UPSTREAM_ERROR
        assertThat(postingRunner.runOnce()).isTrue()
        postWriter.failure = null
        postOf(members.onboarded())
        passRetry()

        assertThat(postingRunner.runOnce()).isTrue()

        val record = records().single()
        assertThat(record["status"]).isEqualTo("GIVEN_UP")
        assertThat(record["give_up_reason"]).isEqualTo("NOT_QUIET")
        assertThat(aiPosts()).isEmpty()
    }

    /** 친구(1, 2, 3)를 글을 가장 오래 쉰 순서로 놓은 것. */
    private fun postingRestOrder(): List<Int> {
        val last = attempts.lastPostedByCompanion()
        val byRest = compareBy<Int> { last[it] ?: Long.MIN_VALUE }.thenBy { it }
        return companions.memberIdByCompanion.keys.sortedWith(byRest)
    }

    private companion object {
        const val CONCURRENCY = 4
    }
}
