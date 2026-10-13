package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.companion.application.ReactionEnroller
import com.ogu.notification.NotificationTestSupport
import com.ogu.post.PostCreated
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
import java.time.Duration

/** 한 글에 AI 친구가 한 명에서 세 명까지 답한다(012 US1). */
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
class CompanionMultiReactionTests : CompanionTestBase() {
    @Autowired
    lateinit var enroller: ReactionEnroller

    @Autowired
    lateinit var redis: StringRedisTemplate

    @Autowired
    lateinit var container: RedisMessageListenerContainer

    @Test
    fun `US1-AC1 반응 기록이 생길 때 반응할 친구의 수가 정해져 남는다`() {
        val author = members.onboarded()

        val posts = listOf(1, 2, 3).associateWith { postOf(author, count = it) }

        posts.forEach { (count, postId) ->
            assertThat(countsOf(postId)).isEqualTo(count to 0)
            assertThat(statusOf(postId)).isEqualTo("WAITING")
        }
    }

    @Test
    fun `US1-AC3 다시 시도하거나 수를 뽑는 설정이 바뀌어도 이미 정해진 수는 그대로다`() {
        val author = members.onboarded()
        val postId = postOf(author, count = 2)

        // 설정이 바뀐 뒤 같은 글의 이벤트가 다시 온다
        countPicker.next = 3
        enroller.enroll(PostCreated(postId, author.id, clock.instant()))
        reactAfter(postId)

        assertThat(countsOf(postId)).isEqualTo(2 to 1)
    }

    @Test
    fun `US1-AC4 다음 친구는 앞 댓글이 올라간 뒤 3분에서 10분 사이의 정해진 때가 되어야 반응한다`() {
        val postId = postOf(members.onboarded(), count = 2)
        reactAfter(postId)
        val left = clock.instant()

        // 첫 친구가 남긴 뒤 기록은 다음 차례를 기다린다. 시도 횟수는 친구마다 새로 센다
        val row = companions.reaction(postId)!!
        assertThat(row["status"]).isEqualTo("WAITING")
        assertThat(row["attempts"]).isEqualTo(0)
        assertThat(countsOf(postId)).isEqualTo(2 to 1)
        val next = companions.nextAttemptAt(postId)!!
        assertThat(next).isBetween(left.plus(Duration.ofMinutes(3)), left.plus(Duration.ofMinutes(10)))

        // 그 전에는 맡지 않는다
        assertThat(runner.attempt(postId)).isFalse()
        companions.moveTo(next.minusSeconds(1))
        assertThat(runner.attempt(postId)).isFalse()
        assertThat(companions.comments(postId)).hasSize(1)

        companions.moveTo(next)
        assertThat(runner.attempt(postId)).isTrue()

        assertThat(companions.comments(postId)).hasSize(2)
        assertThat(statusOf(postId)).isEqualTo("DONE")
    }

    @Test
    fun `US1-AC5 수가 3이면 서로 다른 친구 셋의 댓글과 공감이 하나씩이다`() {
        val postId = postOf(members.onboarded(), count = 3)

        reactAfter(postId)
        followUp(postId)
        followUp(postId)

        val authors = companions.comments(postId).map { it["author_id"] as Long }
        assertThat(authors).hasSize(3).doesNotHaveDuplicates()
        assertThat(authors).containsExactlyInAnyOrderElementsOf(companions.memberIds)
        assertThat(companions.likers(postId)).containsExactlyInAnyOrderElementsOf(companions.memberIds)
        val row = companions.reaction(postId)!!
        assertThat(row["status"]).isEqualTo("DONE")
        assertThat(row["skip_reason"]).isNull()
        assertThat(row["completed_at"]).isNotNull()
        assertThat(countsOf(postId)).isEqualTo(3 to 3)
        assertThat(writer.callsOf(postId)).isEqualTo(3)
        // 더 지나도 맡을 것이 없다
        assertThat(followUp(postId)).isFalse()
    }

    @Test
    fun `US1-AC6 수가 1이면 한 명만 반응한다`() {
        val postId = postOf(members.onboarded(), count = 1)

        reactAfter(postId)

        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(skipReasonOf(postId)).isNull()
        assertThat(followUp(postId)).isFalse()
        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(writer.callsOf(postId)).isEqualTo(1)
    }

    @Test
    fun `US1-AC7 그 글에 아직 반응하지 않은 친구 가운데 가장 오래 쉰 친구가 차례로 고른다`() {
        val postId = postOf(members.onboarded(), count = 3)
        val expected = companions.restOrder()

        reactAfter(postId)
        followUp(postId)
        followUp(postId)

        val order = companions.entries(postId).map { (it["companion_id"] as Number).toInt() }
        assertThat(order).isEqualTo(expected)
        // 다음 글의 첫 친구는 이 글에 가장 먼저 답한 친구다(가장 오래 쉬었다)
        val nextPost = postOf(members.onboarded(), count = 1)
        reactAfter(nextPost)
        val nextFriend = (companions.entries(nextPost).single()["companion_id"] as Number).toInt()
        assertThat(nextFriend).isEqualTo(expected.first())
    }

    @Test
    fun `US1-AC8 친구마다 몬스터 HP가 4 줄고 글쓴이에게 알림이 간다`() {
        val support = NotificationTestSupport(jdbcTemplate, redis, container)
        val author = members.onboarded()
        val postId = postOf(author, "[불안:낮음] 내일 발표가 걱정이에요", count = 2)
        val monster = loop.awaitMonster(postId)

        reactAfter(postId)
        followUp(postId)

        val ais = companions.comments(postId).map { it["author_id"] as Long }
        assertThat(loop.monster(postId)!!.hp).isEqualTo(monster.maxHp - 8)
        assertThat(loop.hpLogs(postId).map { Triple(it.memberId, it.action, it.hpDelta) }).containsExactly(
            Triple(ais[0], "COMMENT", 3),
            Triple(ais[0], "POST_LIKE", 1),
            Triple(ais[1], "COMMENT", 3),
            Triple(ais[1], "POST_LIKE", 1),
        )
        await().atMost(NotificationTestSupport.AWAIT_LIMIT).until {
            support.notificationsOf(author.id, "POST_COMMENT").size == 2 &&
                support.notificationsOf(author.id, "POST_LIKE").sumOf { it.actorCount } == 2
        }
        assertThat(support.notificationsOf(author.id, "POST_COMMENT").map { it.latestActorId })
            .containsExactlyInAnyOrderElementsOf(ais)
    }

    @Test
    fun `US1-AC9 앞 댓글을 되풀이한 답은 올라가지 않고 세 번 걸리면 남은 반응을 그만둔다`() {
        val postId = postOf(members.onboarded(), "[실패] [AI댓글되풀이] 오늘도 야근이에요", count = 2)
        reactAfter(postId)
        val first = companions.comments(postId).single()["content"] as String

        repeat(3) { followUp(postId) }

        assertThat(writer.inputOf(postId)!!.previous).containsExactly(first)
        assertThat(companions.comments(postId).map { it["content"] }).containsExactly(first)
        val row = companions.reaction(postId)!!
        assertThat(row["status"]).isEqualTo("DONE")
        assertThat(row["skip_reason"]).isEqualTo("REJECTED")
        assertThat(row["last_error"]).isEqualTo("REPEATED")
        assertThat(countsOf(postId)).isEqualTo(2 to 1)
        assertThat(writer.callsOf(postId)).isEqualTo(4)
    }

    @Test
    fun `FR-010 뒤따르는 친구에게는 앞서 남긴 AI 친구의 댓글만 보내고 사람의 댓글은 보내지 않는다`() {
        val author = members.onboarded()
        val postId = postOf(author, count = 2)
        // 글쓴이의 댓글은 AI 친구의 반응을 막지 않는다. AI에 보내지도 않는다
        loop.comment(author, postId, "덧붙이자면 내일도 야근이에요")
        reactAfter(postId)
        assertThat(writer.inputOf(postId)!!.previous).isEmpty()
        val first = companions.comments(postId).single()["content"] as String

        followUp(postId)

        assertThat(writer.inputOf(postId)!!.previous).containsExactly(first)
        assertThat(companions.comments(postId)).hasSize(2)
    }

    @Test
    fun `FR-010 숨겨진 AI 친구의 댓글은 다음 친구에게 보내지 않고 그 친구는 다시 반응하지 않는다`() {
        val postId = postOf(members.onboarded(), count = 3)
        reactAfter(postId)
        val hidden = companions.comments(postId).single()
        jdbcTemplate.update(
            "update comments set hidden_at = now(), hidden_reason = 'OPERATOR' where id = ?",
            hidden["id"],
        )

        followUp(postId)
        assertThat(writer.inputOf(postId)!!.previous).isEmpty()
        val second = companions.comments(postId).last()
        followUp(postId)

        assertThat(writer.inputOf(postId)!!.previous).containsExactly(second["content"] as String)
        val authors = companions.comments(postId).map { it["author_id"] as Long }
        assertThat(authors).hasSize(3).doesNotHaveDuplicates()
        assertThat(statusOf(postId)).isEqualTo("DONE")
    }

    @Test
    fun `US7-AC4 기록에 글마다 정해진 수와 친구별 반응이 남고 본문은 없다`() {
        val postId = postOf(members.onboarded(), count = 2)

        reactAfter(postId)
        followUp(postId)

        val comments = companions.comments(postId)
        val entries = companions.entries(postId)
        assertThat(entries.map { it["comment_id"] }).isEqualTo(comments.map { it["id"] })
        assertThat(entries.map { companions.memberIdByCompanion.getValue((it["companion_id"] as Number).toInt()) })
            .isEqualTo(comments.map { it["author_id"] })
        assertThat(entries).allSatisfy { assertThat(it.keys).doesNotContain("content") }
        // 처음 남긴 댓글이 기록의 댓글로 남는다
        assertThat(companions.reaction(postId)!!["comment_id"]).isEqualTo(comments.first()["id"])
    }
}
