package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.post.CommentTone
import com.ogu.support.CompanionSettings
import com.ogu.support.CompanionTestConfiguration
import com.ogu.support.FutureClockSchedulers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/** AI 친구의 글에 다른 AI 친구가 답한다(012 US6, research R19). 따로 만든 길 없이 사람의 글과 같은 규칙을 탄다. */
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
class CompanionPostReactionTests : CompanionPostingTestBase() {
    @Test
    fun `US6-AC1 AI 친구의 글에 글쓴 친구가 아닌 AI 친구가 댓글과 공감을 남긴다`() {
        val (writer, postId) = aiPost(count = 1)

        assertThat(reactAfter(postId)).isTrue()

        val comment = companions.comments(postId).single()
        assertThat(comment["author_id"]).isNotEqualTo(writer)
        assertThat(companions.likers(postId)).containsExactly(comment["author_id"] as Long)
        assertThat(statusOf(postId)).isEqualTo("DONE")
        // AI에는 그 글의 본문이 갔다
        assertThat(this.writer.inputOf(postId)!!.content).contains("지어 쓴 글")
    }

    @Test
    fun `US6-AC2 AI 친구의 글에 반응할 수는 글쓴 친구를 뺀 두 명까지다`() {
        val (_, three) = aiPost(count = 3)
        val (_, one) = aiPost(count = 1)

        assertThat(countsOf(three)).isEqualTo(2 to 0)
        assertThat(countsOf(one)).isEqualTo(1 to 0)
    }

    @Test
    fun `US6-AC3 반응이 모두 끝나도 글쓴 친구의 댓글과 공감은 없다`() {
        val (writer, postId) = aiPost(count = 3)

        reactAfter(postId)
        followUp(postId)

        val authors = companions.comments(postId).map { it["author_id"] as Long }
        assertThat(authors).containsExactlyInAnyOrderElementsOf(companions.memberIds - writer)
        assertThat(companions.likers(postId)).containsExactlyInAnyOrderElementsOf(companions.memberIds - writer)
        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(skipReasonOf(postId)).isNull()
        assertThat(countsOf(postId)).isEqualTo(2 to 2)
        // 더 지나도 글쓴 친구는 오지 않는다
        assertThat(followUp(postId)).isFalse()
    }

    @Test
    fun `US6-AC4 AI 친구의 글에 사람이 먼저 댓글을 달면 AI 친구는 반응하지 않는다`() {
        val (_, postId) = aiPost(count = 2)
        loop.comment(members.onboarded(), postId, "저도 그런 날이 있었어요")

        assertThat(reactAfter(postId)).isTrue()

        assertThat(skipReasonOf(postId)).isEqualTo("HUMAN_COMMENT")
        assertThat(companions.comments(postId)).isEmpty()
        assertThat(this.writer.callsOf(postId)).isZero()
    }

    @Test
    fun `US6-AC5 AI 친구의 글에 반응이 달려도 글쓴 AI 친구에게는 알림이 만들어지지 않는다`() {
        val (writer, postId) = aiPost(count = 2)
        reactAfter(postId)
        followUp(postId)
        loop.comment(members.onboarded(), postId, "응원해요")
        safety.awaitListenersIdle()

        assertThat(companions.notificationCount(writer)).isZero()
        assertThat(companions.comments(postId)).hasSize(2)
    }

    /** AI 친구 한 명의 이름으로 글을 저장하고, 반응 기록이 [count]로 뽑힌 수로 생길 때까지 기다린다. */
    private fun aiPost(count: Int): Pair<Long, Long> {
        val writer = companions.memberIds.first()
        countPicker.next = count
        val postId = companionPostApi.write(writer, "[실패] AI 친구가 지어 쓴 글이에요. 회의가 길었어요.", CommentTone.COMFORT_ME)
        companions.awaitReady(postId)
        countPicker.next = 1
        return writer to postId
    }
}
