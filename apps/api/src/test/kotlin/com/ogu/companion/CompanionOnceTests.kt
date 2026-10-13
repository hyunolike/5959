package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.post.CompanionReactionCheck
import com.ogu.post.CompanionReactionOutcome
import com.ogu.post.PostCompanionApi
import com.ogu.post.PostCreated
import com.ogu.support.CompanionFixture
import com.ogu.support.CompanionSettings
import com.ogu.support.CompanionTestConfiguration
import com.ogu.support.FutureClockSchedulers
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 한 글에는 AI 친구 한 명이 한 번만 반응한다(009 FR-002, SC-004, research R4, R5). */
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
class CompanionOnceTests : CompanionTestBase() {
    @Autowired
    lateinit var companionApi: PostCompanionApi

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var transactions: TransactionTemplate

    @Test
    fun `FR-002 같은 글을 두 실행기가 함께 맡아도 댓글과 공감은 하나씩이다`() {
        val postId = postOf(members.onboarded())
        companions.pass(CompanionFixture.WAIT)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENCY)

        val handled =
            try {
                (1..CONCURRENCY)
                    .map {
                        executor.submit(
                            Callable {
                                start.await(5, TimeUnit.SECONDS)
                                runner.attempt(postId)
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        // 한 실행기만 맡는다. 나머지는 잠긴 행을 건너뛰거나 미뤄진 차례를 보고 물러난다
        assertThat(handled.count { it }).isEqualTo(1)
        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(companions.likers(postId)).hasSize(1)
        assertThat(writer.callsOf(postId)).isEqualTo(1)
    }

    @Test
    fun `FR-002 남긴 뒤 적기 전에 글이 숨겨졌어도 남긴 댓글의 기록은 DONE이다`() {
        val postId = postOf(members.onboarded())
        val left = companionApi.react(postId, companions.memberIds[0], "먼저 남겨진 댓글이에요.", companions.memberIds)
        jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'OPERATOR' where id = ?", postId)

        assertThat(reactAfter(postId)).isTrue()

        val row = companions.reaction(postId)!!
        assertThat(row["status"]).isEqualTo("DONE")
        assertThat(row["comment_id"]).isEqualTo(left.commentId)
        assertThat(writer.callsOf(postId)).isZero()
    }

    @Test
    fun `FR-002 댓글을 남긴 뒤 적기 전에 멈췄다 다시 돌면 DONE으로 닫고 댓글이 늘지 않는다`() {
        val postId = postOf(members.onboarded())
        // 댓글과 공감은 커밋됐는데 기록은 아직 WAITING인 상태
        val left = companionApi.react(postId, companions.memberIds[1], "먼저 남겨진 댓글이에요.", companions.memberIds)
        assertThat(left.outcome).isEqualTo(CompanionReactionOutcome.REACTED)
        assertThat(statusOf(postId)).isEqualTo("WAITING")

        assertThat(reactAfter(postId)).isTrue()

        val row = companions.reaction(postId)!!
        assertThat(row["status"]).isEqualTo("DONE")
        assertThat(row["comment_id"]).isEqualTo(left.commentId)
        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(companions.likers(postId)).hasSize(1)
        // AI에 다시 청하지 않는다
        assertThat(writer.callsOf(postId)).isZero()
    }

    @Test
    fun `FR-002 쓰는 사이에 다른 실행기가 남겼으면 그 댓글로 닫고 하나만 남는다`() {
        val postId = postOf(members.onboarded())
        writer.beforeAnswer(postId) {
            companionApi.react(postId, companions.memberIds[2], "그 사이에 남겨진 댓글이에요.", companions.memberIds)
        }

        reactAfter(postId)

        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(companions.comments(postId).map { it["content"] }).containsExactly("그 사이에 남겨진 댓글이에요.")
        assertThat(companions.likers(postId)).hasSize(1)
    }

    @Test
    fun `US2-AC9 수가 여럿인 글을 여러 실행기가 함께 맡아도 한 번에 한 친구만 남긴다`() {
        val postId = postOf(members.onboarded(), count = 3)
        companions.pass(CompanionFixture.WAIT)

        val handled = together { runner.attempt(postId) }

        assertThat(handled.count { it }).isEqualTo(1)
        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(countsOf(postId)).isEqualTo(3 to 1)
        assertThat(statusOf(postId)).isEqualTo("WAITING")
    }

    @Test
    fun `US2-AC9 두 친구가 같은 글에 함께 남겨도 AI 친구의 댓글은 정해진 수를 넘지 않는다`() {
        val postId = postOf(members.onboarded(), count = 2)
        val ids = companions.memberIds

        // 맡은 시간이 지나 실행기 셋이 같은 글을 서로 다른 친구로 함께 남기는 상황
        val outcomes =
            together(ids.size) { index ->
                companionApi.react(postId, ids[index], "함께 남기는 말 ${index + 1}", ids, upTo(2)).outcome
            }

        assertThat(outcomes.count { it == CompanionReactionOutcome.REACTED }).isEqualTo(2)
        assertThat(outcomes.count { it == CompanionReactionOutcome.FULL }).isEqualTo(1)
        assertThat(companions.comments(postId)).hasSize(2)
        assertThat(companions.likers(postId)).hasSize(2)
    }

    @Test
    fun `US2-AC9 남긴 뒤 적기 전에 멈췄다 다시 돌면 기록이 댓글에 맞춰지고 댓글이 늘지 않는다`() {
        val postId = postOf(members.onboarded(), count = 3)
        val ids = companions.memberIds
        // 두 친구의 댓글과 공감은 커밋됐는데 기록에는 아무것도 적히지 않은 상태
        val left = listOf(ids[0], ids[2]).map { companionApi.react(postId, it, "먼저 남겨진 댓글", ids, upTo(3)) }
        assertThat(countsOf(postId)).isEqualTo(3 to 0)

        assertThat(reactAfter(postId)).isTrue()

        // AI에 청하지 않고 남아 있는 댓글로 기록을 맞춘다. 남은 한 명의 차례는 뒤로 잡힌다
        assertThat(writer.callsOf(postId)).isZero()
        assertThat(countsOf(postId)).isEqualTo(3 to 2)
        assertThat(companions.entries(postId).map { it["comment_id"] }).isEqualTo(left.map { it.commentId })
        assertThat(statusOf(postId)).isEqualTo("WAITING")
        assertThat(runner.attempt(postId)).isFalse()

        assertThat(followUp(postId)).isTrue()

        val authors = companions.comments(postId).map { it["author_id"] as Long }
        assertThat(authors).containsExactly(ids[0], ids[2], ids[1])
        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(countsOf(postId)).isEqualTo(3 to 3)
        assertThat(writer.callsOf(postId)).isEqualTo(1)
    }

    @Test
    fun `US2-AC9 쓰는 사이에 다른 실행기가 정해진 수를 채웠으면 남기지 않고 기록을 맞춰 닫는다`() {
        val postId = postOf(members.onboarded(), count = 2)
        val ids = companions.memberIds
        reactAfter(postId)
        val first = companions.comments(postId).single()["author_id"] as Long
        val others = ids - first
        // 둘째 친구가 쓰는 사이에 다른 실행기가 셋째 친구의 이름으로 먼저 남긴다
        val byCompanion = companions.memberIdByCompanion
        val chosen = byCompanion.getValue(companions.restOrder().first { byCompanion[it] != first })
        val rival = others.single { it != chosen }
        writer.beforeAnswer(postId) { companionApi.react(postId, rival, "그 사이에 남겨진 댓글이에요.", ids, upTo(2)) }

        followUp(postId)

        assertThat(companions.comments(postId).map { it["author_id"] }).containsExactly(first, rival)
        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(skipReasonOf(postId)).isNull()
        assertThat(countsOf(postId)).isEqualTo(2 to 2)
    }

    @Test
    fun `US1-AC2 쓰는 사이에 다른 회원이 댓글을 달았으면 남기지 않는다`() {
        val postId = postOf(members.onboarded())
        val human = members.onboarded()
        writer.beforeAnswer(postId) { loop.comment(human, postId, "제가 먼저 왔어요") }

        reactAfter(postId)

        assertThat(skipReasonOf(postId)).isEqualTo("HUMAN_COMMENT")
        assertThat(companions.comments(postId)).isEmpty()
        assertThat(companions.likers(postId)).isEmpty()
    }

    @Test
    fun `US3-AC2 쓰는 사이에 글이 지워졌으면 남기지 않는다`() {
        val author = members.onboarded()
        val postId = postOf(author)
        writer.beforeAnswer(postId) { loop.removePost(author, postId) }

        reactAfter(postId)

        assertThat(skipReasonOf(postId)).isEqualTo("GONE")
        assertThat(companions.comments(postId)).isEmpty()
    }

    @Test
    fun `FR-002 글이 저장됐다는 이벤트가 다시 와도 반응 기록은 하나다`() {
        val author = members.onboarded()
        val postId = postOf(author)
        val createdAt = companions.reaction(postId)!!["post_created_at"]

        repeat(2) {
            transactions.executeWithoutResult { events.publishEvent(PostCreated(postId, author.id, clock.instant())) }
        }
        val settle = Duration.ofMillis(300)
        await().atMost(CompanionFixture.AWAIT_LIMIT).pollDelay(settle).until { pendingEnrolls(postId) == 0 }

        val rows = jdbcTemplate.queryForList("select * from companion_reaction where post_id = ?", postId)
        assertThat(rows).hasSize(1)
        assertThat(rows.single()["post_created_at"]).isEqualTo(createdAt)
    }

    /** 정해진 수가 [limit]인 글에 남길 때의 재확인. */
    private fun upTo(limit: Int) = CompanionReactionCheck(limit = limit)

    /** [count]개의 스레드가 같은 때에 [action]을 한다. */
    private fun <T> together(
        count: Int = CONCURRENCY,
        action: (index: Int) -> T,
    ): List<T> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(count)
        return try {
            (0 until count)
                .map { index ->
                    executor.submit(
                        Callable {
                            start.await(5, TimeUnit.SECONDS)
                            action(index)
                        },
                    )
                }.also { start.countDown() }
                .map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun pendingEnrolls(postId: Long): Int =
        jdbcTemplate.queryForObject(
            """
            select count(*) from event_publication
            where listener_id like 'com.ogu.companion.%' and completion_date is null
              and (serialized_event::jsonb ->> 'postId') = ?
            """.trimIndent(),
            Int::class.java,
            postId.toString(),
        )!!

    private companion object {
        const val CONCURRENCY = 4
    }
}
