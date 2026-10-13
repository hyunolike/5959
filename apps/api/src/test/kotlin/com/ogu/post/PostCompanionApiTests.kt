package com.ogu.post

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** AI 친구가 글의 상태를 읽고 댓글과 공감을 한 번에 남기는 파사드(009 research R5). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PostCompanionApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var companionApi: PostCompanionApi

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var companions: List<Long>

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        companions = jdbcTemplate.queryForList("select member_id from companion order by id", Long::class.java)
    }

    @Test
    fun `US1-AC1 댓글 하나와 공감 하나가 남는다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        val target = companionApi.target(postId, companions)!!
        val result = companionApi.react(postId, companions[0], "토닥토닥, 오늘 고생 많았어요.", companions)

        assertThat(target.authorId).isEqualTo(author.id)
        assertThat(target.content).isEqualTo("[실패] 분석 중인 글")
        assertThat(target.commentTone).isEqualTo(CommentTone.COMFORT_ME)
        assertThat(target.hidden).isFalse()
        assertThat(target.companionCommentId).isNull()
        assertThat(target.otherCommented).isFalse()
        assertThat(result.outcome).isEqualTo(CompanionReactionOutcome.REACTED)
        assertThat(commentsBy(postId, companions[0])).containsExactly(result.commentId)
        assertThat(likesBy(postId, companions[0])).isEqualTo(1)
        assertThat(counts(postId)).isEqualTo(1 to 1)
        assertThat(companionApi.target(postId, companions)!!.companionCommentId).isEqualTo(result.commentId)
    }

    @Test
    fun `US3-AC3 대상을 읽은 뒤 본문이 고쳐졌으면 STALE이고 아무것도 남지 않는다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val read = companionApi.target(postId, companions)!!

        loop.updatePost(author, postId, mapOf("content" to "[실패] 고친 본문이에요")).andExpect(status().isNoContent)
        val result = companionApi.react(postId, companions[0], "옛 본문에 맞춘 댓글", companions, read.contentVersion)

        assertThat(result.outcome).isEqualTo(CompanionReactionOutcome.STALE)
        assertThat(commentsBy(postId, companions[0])).isEmpty()
        assertThat(likesBy(postId, companions[0])).isZero()
        assertThat(counts(postId)).isEqualTo(0 to 0)
    }

    @Test
    fun `US4-AC1 말투만 고쳤으면 본문의 판이 그대로라 남긴다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val read = companionApi.target(postId, companions)!!

        loop.updatePost(author, postId, mapOf("commentTone" to "WARM_ADVICE")).andExpect(status().isNoContent)
        val result = companionApi.react(postId, companions[0], "곁에 있을게요", companions, read.contentVersion)

        assertThat(companionApi.target(postId, companions)!!.contentVersion).isEqualTo(read.contentVersion)
        assertThat(result.outcome).isEqualTo(CompanionReactionOutcome.REACTED)
    }

    @Test
    fun `FR-016 AI 친구가 아닌 회원의 이름으로는 남기지 않는다`() {
        val author = members.onboarded()
        val human = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        assertThatThrownBy { companionApi.react(postId, human.id, "사람의 이름으로 남기려는 댓글", companions) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(counts(postId)).isEqualTo(0 to 0)
    }

    @Test
    fun `US1-AC2 다른 회원의 댓글이 있으면 HUMAN_COMMENTED이고 아무것도 남지 않는다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.comment(members.onboarded(), postId)

        val result = companionApi.react(postId, companions[0], "힘내요", companions)

        assertThat(companionApi.target(postId, companions)!!.otherCommented).isTrue()
        assertThat(result.outcome).isEqualTo(CompanionReactionOutcome.HUMAN_COMMENTED)
        assertThat(result.commentId).isNull()
        assertThat(commentsBy(postId, companions[0])).isEmpty()
        assertThat(likesBy(postId, companions[0])).isZero()
        assertThat(counts(postId)).isEqualTo(1 to 0)
    }

    @Test
    fun `US1-AC4 글쓴이의 댓글은 다른 회원의 댓글이 아니다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.comment(author, postId, "제가 덧붙여요")

        assertThat(companionApi.target(postId, companions)!!.otherCommented).isFalse()
        val result = companionApi.react(postId, companions[1], "곁에 있을게요", companions)

        assertThat(result.outcome).isEqualTo(CompanionReactionOutcome.REACTED)
    }

    @Test
    fun `US1-AC3 달았다가 지운 댓글과 숨겨진 댓글, 공감만 있는 글은 다른 회원의 댓글이 없는 것이다`() {
        val author = members.onboarded()
        val other = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val deleted = loop.comment(other, postId)
        loop.deleteComment(deleted)
        val hidden = loop.comment(other, postId)
        jdbcTemplate.update("update comments set hidden_at = now(), hidden_reason = 'OPERATOR' where id = ?", hidden)
        loop.likePost(other, postId)

        assertThat(companionApi.target(postId, companions)!!.otherCommented).isFalse()
    }

    @Test
    fun `US3-AC2 지웠거나 숨긴 글이면 GONE이다`() {
        val author = members.onboarded()
        val removed = loop.postWithoutMonster(author)
        val hidden = loop.postWithoutMonster(author)
        loop.deletePost(removed)
        jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'OPERATOR' where id = ?", hidden)

        assertThat(companionApi.target(removed, companions)).isNull()
        assertThat(companionApi.target(hidden, companions)!!.hidden).isTrue()
        for (postId in listOf(removed, hidden, Long.MAX_VALUE)) {
            val result = companionApi.react(postId, companions[0], "힘내요", companions)
            assertThat(result.outcome).isEqualTo(CompanionReactionOutcome.GONE)
            assertThat(commentsBy(postId, companions[0])).isEmpty()
        }
    }

    @Test
    fun `FR-002 두 번 불러도 댓글과 공감은 하나씩이다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val first = companionApi.react(postId, companions[0], "토닥토닥", companions)

        val again = companionApi.react(postId, companions[0], "토닥토닥", companions)
        val another = companionApi.react(postId, companions[2], "다른 친구의 말", companions)

        assertThat(again.outcome).isEqualTo(CompanionReactionOutcome.ALREADY_REACTED)
        assertThat(again.commentId).isEqualTo(first.commentId)
        assertThat(another.outcome).isEqualTo(CompanionReactionOutcome.ALREADY_REACTED)
        assertThat(another.commentId).isEqualTo(first.commentId)
        assertThat(counts(postId)).isEqualTo(1 to 1)
        assertThat(commentsBy(postId, companions[2])).isEmpty()
    }

    @Test
    fun `FR-002 두 친구가 같은 글에 함께 남기려 해도 한 명만 남긴다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val outcomes =
            try {
                listOf(companions[0], companions[1])
                    .map { id ->
                        executor.submit(
                            Callable {
                                start.await(5, TimeUnit.SECONDS)
                                companionApi.react(postId, id, "함께 남기는 말", companions).outcome
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(20, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertThat(outcomes).containsExactlyInAnyOrder(
            CompanionReactionOutcome.REACTED,
            CompanionReactionOutcome.ALREADY_REACTED,
        )
        assertThat(counts(postId)).isEqualTo(1 to 1)
    }

    @Test
    fun `US1-AC1 공감이 실패하면 댓글도 남지 않는다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        // 공감만 먼저 들어가 있는 상태를 만든다(실제로는 생기지 않는다). 공감이 409로 실패한다
        jdbcTemplate.update(
            "insert into post_likes (post_id, member_id, created_at) values (?, ?, now())",
            postId,
            companions[0],
        )

        assertThatThrownBy { companionApi.react(postId, companions[0], "힘내요", companions) }
            .isInstanceOfSatisfying(BusinessException::class.java) {
                assertThat(it.errorCode).isEqualTo(ErrorCode.ALREADY_LIKED)
            }

        assertThat(commentsBy(postId, companions[0])).isEmpty()
        assertThat(counts(postId).first).isZero()
    }

    @Test
    fun `US4-AC4 300자를 넘는 댓글은 사람의 댓글처럼 거절되고 남지 않는다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        assertThatThrownBy { companionApi.react(postId, companions[0], "가".repeat(301), companions) }
            .isInstanceOfSatisfying(BusinessException::class.java) {
                assertThat(it.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
            }

        assertThat(counts(postId)).isEqualTo(0 to 0)
    }

    private fun commentsBy(
        postId: Long,
        memberId: Long,
    ): List<Long> =
        jdbcTemplate.queryForList(
            "select id from comments where post_id = ? and author_id = ? and deleted_at is null order by id",
            Long::class.java,
            postId,
            memberId,
        )

    private fun likesBy(
        postId: Long,
        memberId: Long,
    ): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from post_likes where post_id = ? and member_id = ?",
            Int::class.java,
            postId,
            memberId,
        )!!

    /** 글의 (댓글 수, 공감 수). */
    private fun counts(postId: Long): Pair<Int, Int> =
        jdbcTemplate.queryForObject(
            "select comment_count, like_count from posts where id = ?",
            { rs, _ -> rs.getInt(1) to rs.getInt(2) },
            postId,
        )!!
}
