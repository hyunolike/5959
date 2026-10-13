package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.CompanionTone
import com.ogu.ai.CompanionVoice
import com.ogu.support.CompanionSettings
import com.ogu.support.CompanionTestConfiguration
import com.ogu.support.FutureClockSchedulers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration

/**
 * 운영자가 정하는 것(기다리는 시간, 24시간 기한, 운영 기록)과 세 친구의 말투, 말씨(009 US5, US4). 이 컨텍스트는 기다리는
 * 시간을 3분으로 바꿔 띄운다.
 */
@SpringBootTest(
    properties = [
        CompanionSettings.ENABLED,
        CompanionSettings.NO_SCHEDULER,
        CompanionSettings.LONG_TOKEN,
        CompanionSettings.NO_LIMIT,
        "ogu.companion.wait=3m",
        FutureClockSchedulers.REPORT,
        FutureClockSchedulers.RAID,
        FutureClockSchedulers.EMOTION,
        FutureClockSchedulers.SAFETY,
        FutureClockSchedulers.RECOMMEND,
        FutureClockSchedulers.RECOMMEND_BACKFILL,
    ],
)
@Import(TestcontainersConfiguration::class, CompanionTestConfiguration::class)
class CompanionOperationTests : CompanionTestBase() {
    @Test
    fun `US5-AC3 기다리는 시간을 바꾸면 그 시간이 지난 뒤에 반응한다`() {
        val postId = postOf(members.onboarded())

        assertThat(reactAfter(postId, Duration.ofMinutes(2).plusSeconds(59))).isFalse()
        assertThat(statusOf(postId)).isEqualTo("WAITING")
        assertThat(reactAfter(postId, Duration.ofSeconds(1))).isTrue()

        assertThat(statusOf(postId)).isEqualTo("DONE")
    }

    @Test
    fun `US5-AC4 맡지 못하고 지나간 글 가운데 24시간이 지난 글은 EXPIRED이고 안 지난 글에는 반응한다`() {
        val author = members.onboarded()
        val old = postOf(author)
        companions.pass(Duration.ofHours(24).minus(WAIT))
        val recent = postOf(author)

        // 꺼져 있던 동안처럼 아무도 맡지 않은 채 시간이 흘렀다
        assertThat(reactAfter(old, WAIT)).isTrue()
        assertThat(runner.attempt(recent)).isTrue()

        assertThat(skipReasonOf(old)).isEqualTo("EXPIRED")
        assertThat(companions.comments(old)).isEmpty()
        assertThat(writer.callsOf(old)).isZero()
        assertThat(statusOf(recent)).isEqualTo("DONE")
    }

    @Test
    fun `US5-AC4 24시간이 되기 직전의 글에는 반응한다`() {
        val postId = postOf(members.onboarded())

        reactAfter(postId, Duration.ofHours(24).minusSeconds(1))

        assertThat(statusOf(postId)).isEqualTo("DONE")
    }

    @Test
    fun `US5-AC5 기록에 글과 친구와 건너뛴 까닭이 있고 본문은 없다`() {
        val author = members.onboarded()
        val reacted = postOf(author)
        val skipped = postOf(author)
        loop.comment(members.onboarded(), skipped)

        companions.pass(WAIT)
        runner.attempt(reacted)
        runner.attempt(skipped)

        val done = companions.reaction(reacted)!!
        assertThat(done["post_id"]).isEqualTo(reacted)
        assertThat(done["companion_id"]).isNotNull()
        assertThat(done["comment_id"]).isNotNull()
        assertThat(done["completed_at"]).isNotNull()
        val skip = companions.reaction(skipped)!!
        assertThat(skip["skip_reason"]).isEqualTo("HUMAN_COMMENT")
        assertThat(skip["companion_id"]).isNull()
        // 본문이나 댓글을 담을 열이 없다
        assertThat(done.keys).containsExactlyInAnyOrder(
            "id",
            "post_id",
            "post_created_at",
            "status",
            "skip_reason",
            "companion_id",
            "comment_id",
            "attempts",
            "next_attempt_at",
            "last_error",
            "created_at",
            "completed_at",
        )
    }

    @Test
    fun `US4-AC2 글 여섯 개에 세 친구가 두 번씩 돌아가며 나오고 한 글에는 한 명이다`() {
        val author = members.onboarded()

        val who =
            (1..6).map {
                val postId = postOf(author)
                reactAfter(postId, WAIT)
                val authors = companions.comments(postId).map { it["author_id"] as Long }
                assertThat(authors).hasSize(1)
                authors.single()
            }

        assertThat(who.take(3)).containsExactlyInAnyOrderElementsOf(companions.memberIds)
        assertThat(who.drop(3)).containsExactlyElementsOf(who.take(3))
    }

    @Test
    fun `US4-AC1 글쓴이가 고른 말투와 글의 본문과 감정이 쓰기에 넘어간다`() {
        val author = members.onboarded()
        val body = "[불안:낮음] 내일 임원 보고가 걱정이에요"
        val tones =
            mapOf(
                "VENT_WITH_ME" to CompanionTone.VENT_WITH_ME,
                "COMFORT_ME" to CompanionTone.COMFORT_ME,
                "WARM_ADVICE" to CompanionTone.WARM_ADVICE,
                "MAKE_ME_LAUGH" to CompanionTone.MAKE_ME_LAUGH,
            )

        tones.forEach { (tone, expected) ->
            val postId = postOf(author, body, tone)
            loop.awaitMonster(postId)
            reactAfter(postId, WAIT)

            val input = writer.inputOf(postId)!!
            assertThat(input.tone).isEqualTo(expected)
            assertThat(input.content).isEqualTo(body)
            assertThat(input.emotion).isEqualTo(ClassifiedEmotion.ANXIETY)
            // 가짜 쓰기는 말투의 이름을 댓글에 넣는다
            assertThat(companions.comments(postId).single()["content"] as String).contains(expected.label)
        }
    }

    @Test
    fun `US4-AC1 감정 분석이 끝나지 않은 글은 감정 없이 쓴다`() {
        val postId = postOf(members.onboarded(), "[실패] 분석이 끝나지 않는 글")

        reactAfter(postId, WAIT)

        assertThat(writer.inputOf(postId)!!.emotion).isNull()
        assertThat(statusOf(postId)).isEqualTo("DONE")
    }

    @Test
    fun `US4-AC3 친구가 다르면 넘어가는 말씨가 다르고 댓글의 닉네임도 다르다`() {
        val author = members.onboarded()

        val voices =
            (1..3).map {
                val postId = postOf(author)
                reactAfter(postId, WAIT)
                val nickname =
                    jdbcTemplate.queryForObject(
                        "select nickname from member where id = ?",
                        String::class.java,
                        companions.comments(postId).single()["author_id"],
                    )
                val voice = writer.inputOf(postId)!!.voice
                // 말씨의 이름이 그 친구의 닉네임이다
                assertThat(voice.label).isEqualTo(nickname)
                voice
            }

        assertThat(voices).containsExactlyInAnyOrder(*CompanionVoice.entries.toTypedArray())
    }

    private companion object {
        val WAIT: Duration = Duration.ofMinutes(3)
    }
}
