package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.emotion.application.AnalysisRunner
import com.ogu.notification.NotificationTestSupport
import com.ogu.support.CompanionFixture
import com.ogu.support.CompanionSettings
import com.ogu.support.CompanionTestConfiguration
import com.ogu.support.FutureClockSchedulers
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration

/** 반응 없는 글에 AI 친구가 먼저 답한다(009 US1). */
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
class CompanionReactionTests : CompanionTestBase() {
    @Autowired
    lateinit var redis: StringRedisTemplate

    @Autowired
    lateinit var container: RedisMessageListenerContainer

    @Autowired
    lateinit var analysisRunner: AnalysisRunner

    @Test
    fun `US1-AC1 댓글이 없는 글은 기다리는 시간이 지나면 댓글 하나와 공감 하나를 받는다`() {
        val postId = postOf(members.onboarded())

        // 기다리는 시간 전에는 맡지 않는다
        assertThat(reactAfter(postId, Duration.ofMinutes(9).plusSeconds(59))).isFalse()
        assertThat(companions.comments(postId)).isEmpty()
        assertThat(reactAfter(postId, Duration.ofSeconds(1))).isTrue()

        val comment = companions.comments(postId).single()
        val row = companions.reaction(postId)!!
        assertThat(row["status"]).isEqualTo("DONE")
        assertThat(row["comment_id"]).isEqualTo(comment["id"])
        assertThat(row["attempts"]).isEqualTo(1)
        assertThat(row["skip_reason"]).isNull()
        assertThat(row["completed_at"]).isNotNull()
        val companionId = (row["companion_id"] as Number).toInt()
        assertThat(comment["author_id"]).isEqualTo(companions.memberIdByCompanion.getValue(companionId))
        assertThat(companions.likers(postId)).containsExactly(comment["author_id"] as Long)
        assertThat(writer.callsOf(postId)).isEqualTo(1)
    }

    @Test
    fun `US1-AC2 다른 회원이 먼저 댓글을 달았으면 반응하지 않고 AI를 부르지 않는다`() {
        val postId = postOf(members.onboarded())
        loop.comment(members.onboarded(), postId)

        assertThat(reactAfter(postId)).isTrue()

        assertThat(statusOf(postId)).isEqualTo("SKIPPED")
        assertThat(skipReasonOf(postId)).isEqualTo("HUMAN_COMMENT")
        assertThat(companions.comments(postId)).isEmpty()
        assertThat(companions.likers(postId)).isEmpty()
        assertThat(writer.callsOf(postId)).isZero()
    }

    @Test
    fun `US1-AC3 공감만 받은 글에는 반응한다`() {
        val postId = postOf(members.onboarded())
        loop.likePost(members.onboarded(), postId).andExpect(status().isOk)

        reactAfter(postId)

        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(companions.comments(postId)).hasSize(1)
    }

    @Test
    fun `US1-AC4 글쓴이의 댓글만 있으면 반응한다`() {
        val author = members.onboarded()
        val postId = postOf(author)
        loop.comment(author, postId, "덧붙이자면 오늘도 야근이에요")

        reactAfter(postId)

        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(companions.comments(postId)).hasSize(1)
    }

    @Test
    fun `US1-AC5 몬스터 HP가 4 줄고 공격 기록이 댓글 3과 공감 1이다`() {
        val postId = postOf(members.onboarded(), "[불안:낮음] 내일 발표가 걱정이에요")
        loop.awaitMonster(postId)

        reactAfter(postId)

        val aiId = companions.comments(postId).single()["author_id"] as Long
        assertThat(loop.monster(postId)!!.hp).isEqualTo(6)
        val logs = loop.hpLogs(postId)
        assertThat(logs.map { Triple(it.memberId, it.action, it.hpDelta) })
            .containsExactly(Triple(aiId, "COMMENT", 3), Triple(aiId, "POST_LIKE", 1))
        assertThat(logs).allSatisfy { assertThat(it.retroactive).isFalse() }
    }

    @Test
    fun `US1-AC6 글쓴이에게 댓글 알림과 공감 알림이 온다`() {
        val support = NotificationTestSupport(jdbcTemplate, redis, container)
        val author = members.onboarded()
        val postId = postOf(author)

        reactAfter(postId)

        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
            support.notificationsOf(author.id, "POST_COMMENT", "POST_LIKE").size == 2
        }
        val comment = companions.comments(postId).single()
        val rows = support.notificationsOf(author.id, "POST_COMMENT", "POST_LIKE").associateBy { it.type }
        assertThat(rows.getValue("POST_COMMENT").commentId).isEqualTo(comment["id"])
        assertThat(rows.getValue("POST_COMMENT").latestActorId).isEqualTo(comment["author_id"])
        assertThat(rows.getValue("POST_LIKE").latestActorId).isEqualTo(comment["author_id"])
        assertThat(rows.getValue("POST_LIKE").actorCount).isEqualTo(1)
    }

    @Test
    fun `US1-AC7 반응한 글은 시간이 지나거나 고쳐져도 다시 반응하지 않는다`() {
        val author = members.onboarded()
        val postId = postOf(author)
        reactAfter(postId)

        companions.pass(Duration.ofHours(1))
        assertThat(runner.attempt(postId)).isFalse()
        loop.updatePost(author, postId, mapOf("content" to "[실패] 고친 글이에요")).andExpect(status().isNoContent)
        companions.awaitRisk(postId) { it == "DONE" }

        assertThat(reactAfter(postId)).isFalse()
        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(writer.callsOf(postId)).isEqualTo(1)
    }

    @Test
    fun `US1-AC8 몬스터가 아직 없으면 댓글과 공감은 남고 HP는 몬스터가 생길 때 반영된다`() {
        // 감정 분석이 한 번 실패해 몬스터가 아직 없다
        val postId = postOf(members.onboarded(), "[실패:1] 요즘 일이 손에 안 잡혀요")

        reactAfter(postId)

        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(companions.likers(postId)).hasSize(1)
        assertThat(loop.monster(postId)).isNull()

        // 다시 분석해 몬스터가 생기면 그때까지의 반응이 함께 반영된다
        analysisRunner.attempt(postId)
        val monster = loop.awaitMonster(postId)
        await().atMost(CompanionFixture.AWAIT_LIMIT).until { loop.hpLogs(postId).size == 2 }
        assertThat(loop.monster(postId)!!.hp).isEqualTo(monster.maxHp - 4)
        assertThat(loop.hpLogs(postId)).allSatisfy { assertThat(it.retroactive).isTrue() }
    }

    @Test
    fun `US1-AC9 AI 친구의 댓글에 공감하고 답글을 달 수 있고 AI 친구는 답글에 다시 답하지 않는다`() {
        val author = members.onboarded()
        val postId = postOf(author)
        reactAfter(postId)
        val commentId = companions.comments(postId).single()["id"] as Long

        loop.likeComment(author, commentId).andExpect(status().isOk)
        loop.comment(author, postId, "고마워요, 그런데 내일은 어떡하죠?", parentId = commentId)

        assertThat(reactAfter(postId, Duration.ofHours(2))).isFalse()
        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(writer.callsOf(postId)).isEqualTo(1)
    }
}
