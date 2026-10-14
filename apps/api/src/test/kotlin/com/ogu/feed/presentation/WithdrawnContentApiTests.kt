package com.ogu.feed.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.emotion.application.AnalysisRunner
import com.ogu.monster.MonsterApi
import com.ogu.notification.application.NotificationDraft
import com.ogu.notification.application.NotificationWriter
import com.ogu.post.PostReportApi
import com.ogu.post.application.WithdrawnContentCleaner
import com.ogu.post.application.WithdrawnContentRemover
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.RecommendFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
import com.ogu.support.WithdrawalFixture
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 탈퇴한 회원의 글과 기록이 사라지고(013 US2), 다른 회원이 받은 것은 그대로다(US3). 화면에 닿는 조회를 실제 API로 부른다.
 * 탈퇴는 새 상태를 만들지 않고 지운 글과 같은 표지를 쓰므로(research R3), 여기서 보는 것은 "지운 글의 규칙이 탈퇴에도
 * 그대로 적용되는가"다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WithdrawnContentApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var postReportApi: PostReportApi

    @Autowired
    lateinit var analysisRunner: AnalysisRunner

    @Autowired
    lateinit var notificationWriter: NotificationWriter

    @Autowired
    lateinit var remover: WithdrawnContentRemover

    @Autowired
    lateinit var cleaner: WithdrawnContentCleaner

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var safety: SafetyFixture
    private lateinit var withdrawal: WithdrawalFixture
    private lateinit var recommend: RecommendFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        withdrawal = WithdrawalFixture(mockMvc, jdbcTemplate)
        recommend = RecommendFixture(jdbcTemplate)
    }

    @Test
    fun `US2-AC1 US2-AC2 탈퇴한 회원의 글은 피드에 없고 주소로 열면 지운 글의 응답이다`() {
        val leaver = members.onboarded()
        val other = members.onboarded()
        val postId = loop.postWithMonster(leaver)
        assertThat(safety.feedPostIds(other)).contains(postId)

        withdrawal.withdraw(leaver).andExpect(status().isNoContent)

        // US2-AC7: 정리를 기다리지 않는다. 탈퇴가 끝났다는 응답이 온 때부터 보이지 않는다
        assertThat(safety.feedPostIds(other)).doesNotContain(postId)
        loop
            .detail(other, postId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        loop.likePost(other, postId).andExpect(status().isNotFound)
        loop.writeComment(other, postId, "늦은 댓글").andExpect(status().isNotFound)
    }

    @Test
    fun `US2-AC3 탈퇴한 회원의 글은 방문자의 피드에 없다`() {
        val leaver = members.onboarded()
        val postId = loop.postWithMonster(leaver)
        await().atMost(WithdrawalFixture.AWAIT_LIMIT).until { postId in publicFeedIds() }

        withdrawal.withdraw(leaver).andExpect(status().isNoContent)

        assertThat(publicFeedIds()).doesNotContain(postId)
    }

    @Test
    fun `US2-AC4 다른 회원의 글에 남긴 댓글과 그 답글이 사라지고 댓글 수가 그만큼 준다`() {
        val leaver = members.onboarded()
        val author = members.onboarded()
        val third = members.onboarded()
        val postId = loop.postWithMonster(author)
        val mine = loop.comment(leaver, postId, "탈퇴할 회원의 댓글")
        val reply = loop.comment(author, postId, "그 댓글의 답글", parentId = mine)
        val kept = loop.comment(third, postId, "남을 댓글")
        val myReply = loop.comment(leaver, postId, "남의 댓글에 단 답글", parentId = kept)
        assertThat(commentCount(postId)).isEqualTo(4)

        withdrawal.withdrawAndClean(leaver)

        assertThat(commentCount(postId)).isEqualTo(1)
        val items = safety.data(author, "/api/v1/posts/$postId/comments").get("items")
        assertThat(items.values().map { it.get("commentId").asLong() }).containsExactly(kept)
        assertThat(items.get(0).get("replies")).isEmpty()
        assertThat(deleted("comments", mine, reply, myReply)).isEqualTo(3)
        // US2-AC8: 내 댓글의 본문은 비우고, 함께 지워진 다른 회원의 답글은 그 회원의 것이라 그대로 둔다
        assertThat(content("comments", mine)).isEmpty()
        assertThat(content("comments", myReply)).isEmpty()
        assertThat(content("comments", reply)).isEqualTo("그 댓글의 답글")
    }

    @Test
    fun `US2-AC5 US2-AC6 다른 회원의 내 댓글과 공감한 글에서 탈퇴한 회원의 글이 빠진다`() {
        val leaver = members.onboarded()
        val other = members.onboarded()
        val gone = loop.postWithMonster(leaver)
        val stays = loop.postWithMonster(members.onboarded())
        loop.comment(other, gone)
        loop.likePost(other, gone).andExpect(status().isOk)
        loop.comment(other, stays)
        loop.likePost(other, stays).andExpect(status().isOk)

        withdrawal.withdraw(leaver).andExpect(status().isNoContent)

        val myComments = safety.data(other, "/api/v1/members/me/comments").get("items")
        assertThat(myComments.values().map { it.get("postId").asLong() }).containsExactly(stays)
        val liked = safety.data(other, "/api/v1/members/me/liked-posts").get("items")
        assertThat(liked.values().map { it.get("postId").asLong() }).containsExactly(stays)
    }

    @Test
    fun `US2-AC8 정리 뒤 글과 댓글의 본문과 추천의 값이 남아 있지 않다`() {
        val leaver = members.onboarded()
        val live = loop.createPost(leaver, "[불안:낮음] ${recommend.topic()} 살아 있던 글")
        val removedEarlier = loop.createPost(leaver, "[불안:낮음] ${recommend.topic()} 탈퇴 전에 스스로 지운 글")
        recommend.awaitEmbedded(live, removedEarlier)
        loop.removePost(leaver, removedEarlier).andExpect(status().isNoContent)
        assertThat(content("posts", removedEarlier)).isNotEmpty()

        withdrawal.withdrawAndClean(leaver)

        assertThat(content("posts", live)).isEmpty()
        assertThat(content("posts", removedEarlier)).isEmpty()
        assertThat(withdrawal.contentsLeft(leaver.id)).isZero()
        recommend.awaitGone(live)
    }

    @Test
    fun `US2-AC8 감정 분석이 글을 읽고 남긴 근거도 지워지고 감정은 남는다`() {
        val leaver = members.onboarded()
        val live = loop.postWithMonster(leaver)
        val removedEarlier = loop.postWithMonster(leaver)
        assertThat(analysisReason(live)).isNotBlank()
        // 회원이 스스로 지운 글의 근거도 같은 이벤트로 지워진다
        loop.removePost(leaver, removedEarlier).andExpect(status().isNoContent)
        await().atMost(WithdrawalFixture.AWAIT_LIMIT).until { analysisReason(removedEarlier) == null }

        withdrawal.withdrawAndClean(leaver)

        await().atMost(WithdrawalFixture.AWAIT_LIMIT).until { analysisReason(live) == null }
        assertThat(analysisStatus(live)).isEqualTo("ANALYZED")
    }

    @Test
    fun `US2-AC8 받은 알림과 주간 리포트가 지워진다`() {
        val leaver = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithMonster(leaver)
        loop.comment(fan, postId)
        await().atMost(WithdrawalFixture.AWAIT_LIMIT).until { notifications(leaver.id) >= 2 }
        jdbcTemplate.update(
            "insert into weekly_report (member_id, week_start, post_count, emotion_counts, unanalyzed_count, " +
                "defeated_count, received_likes, received_comments, letter_status, letter, published_at) " +
                "values (?, cast('2026-10-05' as date), 1, '{}', 0, 0, 0, 1, 'DONE', '한 주 동안 애썼어요', now())",
            leaver.id,
        )

        withdrawal.withdrawAndClean(leaver)

        assertThat(notifications(leaver.id)).isZero()
        val sequences = "select count(*) from notification_sequence where member_id = ?"
        assertThat(withdrawal.count(sequences, leaver.id)).isZero()
        assertThat(withdrawal.count("select count(*) from weekly_report where member_id = ?", leaver.id)).isZero()
    }

    @Test
    fun `US2-AC9 끝나지 않은 감정 분석은 본문 없이 닫히고 몬스터가 생기지 않는다`() {
        val leaver = members.onboarded()
        val postId = loop.postWithoutMonster(leaver)
        // 첫 시도가 실패로 끝나 다음 차례를 기다리는 상태로 만든다
        await().atMost(WithdrawalFixture.AWAIT_LIMIT).until { analysisAttempts(postId) >= 1 }
        assertThat(analysisStatus(postId)).isEqualTo("PENDING")

        withdrawal.withdrawAndClean(leaver)
        jdbcTemplate.update(
            "update emotion_analysis set next_attempt_at = now() - interval '1 minute' where post_id = ?",
            postId,
        )
        analysisRunner.attempt(postId)

        assertThat(analysisStatus(postId)).isEqualTo("DEFAULTED")
        assertThat(loop.monster(postId)).isNull()
    }

    @Test
    fun `US2-AC10 FR-011 탈퇴한 회원은 주간 리포트의 대상이 아니고 새 알림을 받지 않는다`() {
        val leaver = members.onboarded()
        loop.postWithMonster(leaver)
        val from = Instant.now().minus(1, ChronoUnit.HOURS)
        val until = Instant.now().plus(1, ChronoUnit.HOURS)
        assertThat(postReportApi.authorIdsBetween(from, until, leaver.id - 1, 1)).containsExactly(leaver.id)

        withdrawal.withdrawAndClean(leaver)

        assertThat(postReportApi.authorIdsBetween(from, until, leaver.id - 1, 1)).doesNotContain(leaver.id)
        TransactionTemplate(transactionManager).executeWithoutResult {
            val draft = NotificationDraft.weeklyReport(leaver.id, LocalDate.parse("2026-10-05"))
            notificationWriter.writeAll(listOf(draft))
        }
        assertThat(notifications(leaver.id)).isZero()
    }

    @Test
    fun `FR-015 탈퇴한 회원의 글과 댓글에 걸린 열린 신고는 닫히고 그 회원이 낸 신고는 남는다`() {
        val leaver = members.onboarded()
        val other = members.onboarded()
        val leaverPost = loop.postWithMonster(leaver)
        val otherPost = loop.postWithMonster(other)
        val leaverComment = loop.comment(leaver, otherPost, "신고당할 댓글")
        report(other, "POST", leaverPost)
        report(other, "COMMENT", leaverComment)
        report(leaver, "POST", otherPost)

        withdrawal.withdrawAndClean(leaver)

        assertThat(reportStatus("POST", leaverPost)).isEqualTo("CLOSED")
        assertThat(reportStatus("COMMENT", leaverComment)).isEqualTo("CLOSED")
        // US3-AC8: 다른 회원의 글에 낸 신고는 열린 채 남아 운영자가 처리한다
        assertThat(reportStatus("POST", otherPost)).isEqualTo("PENDING")
    }

    @Test
    fun `US3-AC1 US3-AC2 다른 회원의 글의 공감 수와 몬스터 HP는 탈퇴 전과 같다`() {
        val leaver = members.onboarded()
        val author = members.onboarded()
        val postId = loop.postWithMonster(author)
        loop.likePost(leaver, postId).andExpect(status().isOk)
        val commentId = loop.comment(leaver, postId)
        loop.likeComment(author, commentId)
        assertThat(likeCount(postId)).isEqualTo(1)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(10 - 1 - 3)

        withdrawal.withdrawAndClean(leaver)

        assertThat(likeCount(postId)).isEqualTo(1)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(10 - 1 - 3)
        assertThat(safety.data(author, "/api/v1/posts/$postId").get("likeCount").asInt()).isEqualTo(1)
    }

    @Test
    fun `US3-AC2 탈퇴한 회원이 처치한 몬스터는 처치된 채이고 함께 물리쳐 준 회원의 수도 그대로다`() {
        val leaver = members.onboarded()
        val author = members.onboarded()
        val postId = loop.postWithMonster(author)
        defeat(postId, leaver)

        withdrawal.withdrawAndClean(leaver)

        assertThat(loop.monster(postId)!!.hp).isZero()
        // US3-AC5: 내 몬스터의 카드에 보이는 함께 물리쳐 준 회원의 수
        val card = cards(author).single { it.get("postId").asLong() == postId }
        assertThat(card.get("helperCount").asInt()).isEqualTo(1)
    }

    @Test
    fun `US3-AC6 탈퇴한 회원의 글에서 얻은 카드는 다른 회원의 도감에서 빠진다`() {
        val leaver = members.onboarded()
        val helper = members.onboarded()
        val postId = loop.postWithMonster(leaver)
        defeat(postId, helper)
        assertThat(cards(helper).map { it.get("postId").asLong() }).containsExactly(postId)

        withdrawal.withdraw(leaver).andExpect(status().isNoContent)

        assertThat(cards(helper)).isEmpty()
    }

    @Test
    fun `US3-AC4 다른 회원이 받은 알림은 남고 행동한 회원이 비어 있다`() {
        val leaver = members.onboarded()
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.comment(leaver, postId)
        loop.likePost(leaver, postId).andExpect(status().isOk)
        await().atMost(WithdrawalFixture.AWAIT_LIMIT).until { notifications(author.id) >= 2 }

        withdrawal.withdrawAndClean(leaver)

        val items = safety.data(author, "/api/v1/notifications").get("items").values()
        val fromLeaver = items.filter { it.get("type").asString() in setOf("POST_COMMENT", "POST_LIKE") }
        assertThat(fromLeaver).hasSize(2)
        assertThat(fromLeaver).allMatch { it.get("actor").isNull }
        // 닉네임이 응답 어디에도 남지 않는다
        assertThat(jsonMapper.writeValueAsString(fromLeaver)).doesNotContain("\"nickname\"")
    }

    @Test
    fun `US5-AC2 US5-AC4 같은 정리를 다시 해도 댓글 수가 두 번 줄지 않는다`() {
        val leaver = members.onboarded()
        val author = members.onboarded()
        val postId = loop.postWithMonster(author)
        loop.comment(leaver, postId)
        loop.comment(author, postId, "남을 댓글")
        withdrawal.withdrawAndClean(leaver)
        assertThat(commentCount(postId)).isEqualTo(1)

        val again =
            TransactionTemplate(transactionManager).execute { remover.removeAll(leaver.id, Instant.now()) }!!
        cleaner.clean(leaver.id)

        assertThat(again.posts + again.comments).isZero()
        assertThat(commentCount(postId)).isEqualTo(1)
        assertThat(withdrawal.contentsLeft(leaver.id)).isZero()
    }

    private fun defeat(
        postId: Long,
        helper: TestMember,
    ) {
        jdbcTemplate.update("update monsters set hp = 4 where post_id = ?", postId)
        loop.likePost(helper, postId).andExpect(status().isOk)
        loop.comment(helper, postId)
        assertThat(loop.monster(postId)!!.hp).isZero()
    }

    private fun cards(member: TestMember) =
        safety
            .data(member, "/api/v1/members/me/monster-cards")
            .get("items")
            .values()
            .toList()

    private fun publicFeedIds(): List<Long> {
        val body =
            mockMvc
                .perform(get("/api/v1/public/feed"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        return jsonMapper
            .readTree(body.contentAsString)
            .get("data")
            .get("items")
            .values()
            .map { it.get("postId").asLong() }
    }

    private fun report(
        reporter: TestMember,
        targetType: String,
        targetId: Long,
    ) {
        val body = mapOf("targetType" to targetType, "targetId" to targetId, "reason" to "SPAM")
        safety.send(reporter, HttpMethod.POST, "/api/v1/reports", body).andExpect(status().is2xxSuccessful)
    }

    private fun reportStatus(
        targetType: String,
        targetId: Long,
    ): String =
        jdbcTemplate.queryForObject(
            "select status from report where target_type = ? and target_id = ?",
            String::class.java,
            targetType,
            targetId,
        )!!

    private fun analysisStatus(postId: Long): String? =
        jdbcTemplate
            .queryForList("select status from emotion_analysis where post_id = ?", String::class.java, postId)
            .firstOrNull()

    private fun analysisReason(postId: Long): String? =
        jdbcTemplate
            .queryForList("select reason from emotion_analysis where post_id = ?", String::class.java, postId)
            .firstOrNull()

    private fun analysisAttempts(postId: Long): Int =
        jdbcTemplate
            .queryForList("select attempts from emotion_analysis where post_id = ?", Int::class.java, postId)
            .firstOrNull() ?: 0

    private fun notifications(receiverId: Long): Int =
        withdrawal.count("select count(*) from notification where receiver_id = ?", receiverId)

    private fun commentCount(postId: Long): Int {
        val sql = "select comment_count from posts where id = ?"
        return withdrawal.count(sql, postId)
    }

    private fun likeCount(postId: Long): Int = withdrawal.count("select like_count from posts where id = ?", postId)

    private fun content(
        table: String,
        id: Long,
    ): String = jdbcTemplate.queryForObject("select content from $table where id = ?", String::class.java, id)!!

    private fun deleted(
        table: String,
        vararg ids: Long,
    ): Int {
        val sql = "select count(*) from $table where id = ? and deleted_at is not null"
        return ids.count { withdrawal.count(sql, it) == 1 }
    }
}
