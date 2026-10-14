package com.ogu.post

import com.ogu.TestcontainersConfiguration
import com.ogu.member.application.SessionService
import com.ogu.member.application.WithdrawalService
import com.ogu.monster.MonsterApi
import com.ogu.post.application.CommentService
import com.ogu.post.application.PostService
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.QueryCounter
import com.ogu.support.WithdrawalFixture
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 탈퇴와 겹친 쓰기, 그리고 글이 많은 회원의 탈퇴(013 research R4, R5, SC-006).
 *
 * 탈퇴는 회원 행을 `FOR UPDATE`로 잠그고, 그 회원의 글과 댓글의 INSERT는 FK 때문에 같은 행을 기다린다. 탈퇴가 먼저면
 * 기다리던 INSERT가 통과한 직후에 탈퇴를 보고 되돌린다. 겹침은 탈퇴의 트랜잭션을 커밋 직전에 붙잡아 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WithdrawalRaceTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var withdrawalService: WithdrawalService

    @Autowired
    lateinit var postService: PostService

    @Autowired
    lateinit var commentService: CommentService

    @Autowired
    lateinit var sessionService: SessionService

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var withdrawal: WithdrawalFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        withdrawal = WithdrawalFixture(mockMvc, jdbcTemplate)
    }

    @Test
    fun `탈퇴가 커밋되는 사이에 들어온 글과 댓글은 올라가지 않는다`() {
        val leaver = members.onboarded()
        val otherPost = loop.postWithoutMonster(members.onboarded())
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)

        val withdrawing =
            CompletableFuture.runAsync {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    withdrawalService.withdraw(leaver.id)
                    locked.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        // 아직 커밋되지 않은 탈퇴는 보이지 않는다. 두 쓰기는 프로필을 읽고 INSERT에서 회원 행을 기다린다
        val writingPost = attempt { postService.create(leaver.id, "겹쳐 들어온 글", CommentTone.COMFORT_ME) }
        val writingComment = attempt { commentService.write(otherPost, leaver.id, "겹쳐 들어온 댓글", null) }
        Thread.sleep(BLOCK_MILLIS)
        assertThat(writingPost.isDone).isFalse()
        assertThat(writingComment.isDone).isFalse()

        release.countDown()
        withdrawing.get(WAIT_SECONDS, TimeUnit.SECONDS)

        assertThat(writingPost.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ErrorCode.SESSION_EXPIRED)
        assertThat(writingComment.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ErrorCode.SESSION_EXPIRED)
        assertThat(withdrawal.count("select count(*) from posts where author_id = ?", leaver.id)).isZero()
        assertThat(withdrawal.count("select count(*) from comments where author_id = ?", leaver.id)).isZero()
        assertThat(withdrawal.count("select comment_count from posts where id = ?", otherPost)).isZero()
    }

    @Test
    fun `탈퇴가 커밋되는 사이의 로그인은 세션을 얻지 못한다`() {
        val leaver = members.onboarded()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)

        val withdrawing =
            CompletableFuture.runAsync {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    withdrawalService.withdraw(leaver.id)
                    locked.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        // 비밀번호를 견준 뒤 세션을 만드는 자리다. 세션의 INSERT가 회원 행을 기다린다
        val issuing = attempt { sessionService.issue(leaver.id, onboarded = true) }
        Thread.sleep(BLOCK_MILLIS)
        assertThat(issuing.isDone).isFalse()

        release.countDown()
        withdrawing.get(WAIT_SECONDS, TimeUnit.SECONDS)

        assertThat(issuing.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ErrorCode.SESSION_EXPIRED)
        assertThat(withdrawal.count("select count(*) from auth_session where member_id = ?", leaver.id)).isZero()
    }

    @Test
    fun `서로의 글에 댓글을 단 두 회원이 함께 탈퇴해도 둘 다 끝난다`() {
        val first = members.onboarded()
        val second = members.onboarded()
        val firstPost = loop.postWithoutMonster(first)
        val secondPost = loop.postWithoutMonster(second)
        repeat(CROSS_COMMENTS) {
            val onFirst = loop.comment(second, firstPost, "서로의 글에 단 댓글")
            loop.comment(first, firstPost, "그 댓글의 답글", parentId = onFirst)
            val onSecond = loop.comment(first, secondPost, "서로의 글에 단 댓글")
            loop.comment(second, secondPost, "그 댓글의 답글", parentId = onSecond)
        }

        val both = listOf(first, second).map { CompletableFuture.runAsync { withdrawalService.withdraw(it.id) } }
        both.forEach { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }

        withdrawal.awaitCleaned(first.id)
        withdrawal.awaitCleaned(second.id)
        assertThat(withdrawal.contentsLeft(first.id) + withdrawal.contentsLeft(second.id)).isZero()
    }

    @Test
    fun `글쓰기가 먼저 커밋되면 탈퇴가 그 글까지 지운다`() {
        val leaver = members.onboarded()
        val postId = postService.create(leaver.id, "탈퇴 직전에 올린 글", CommentTone.COMFORT_ME)

        withdrawal.withdrawAndClean(leaver)

        val removed = "select count(*) from posts where id = ? and deleted_at is not null"
        assertThat(withdrawal.count(removed, postId)).isOne()
        assertThat(withdrawal.contentsLeft(leaver.id)).isZero()
    }

    @Test
    fun `SC-006 글 1000개와 댓글 5000개인 회원의 탈퇴도 문장 수가 같고 정리가 끝까지 간다`() {
        val small = members.onboarded()
        val large = members.onboarded()
        val otherPost = loop.postWithoutMonster(members.onboarded())
        // 견줄 회원은 글 하나와 댓글 하나다. 글이 하나도 없으면 글 잠금 문장이 빠져 수가 하나 적다
        loop.postWithoutMonster(small)
        loop.comment(small, loop.postWithoutMonster(members.onboarded()))
        seed(large.id, otherPost)

        val (_, smallQueries) = QueryCounter.count { withdrawalService.withdraw(small.id) }
        val started = System.nanoTime()
        val (_, largeQueries) = QueryCounter.count { withdrawalService.withdraw(large.id) }
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        // 글과 댓글이 몇 개든 같은 트랜잭션에서 도는 문장 수는 같다(research R2)
        assertThat(largeQueries).isEqualTo(smallQueries)
        assertThat(elapsed).isLessThan(RESPONSE_LIMIT)
        val live = "select count(*) from posts where author_id = ? and deleted_at is null"
        assertThat(withdrawal.count(live, large.id)).isZero()
        assertThat(withdrawal.count("select comment_count from posts where id = ?", otherPost)).isZero()
        // 본문은 뒤에서 나눠 비운다
        await().atMost(CLEANUP_LIMIT).pollInterval(Duration.ofMillis(200)).until {
            withdrawal.pendingCleanups(large.id) == 0
        }
        assertThat(withdrawal.contentsLeft(large.id)).isZero()
    }

    /** 다른 스레드에서 돌려 받은 오류 코드를 준다. 성공하면 null이다. */
    private fun attempt(write: () -> Any?): CompletableFuture<ErrorCode?> =
        CompletableFuture.supplyAsync {
            try {
                write()
                null
            } catch (e: BusinessException) {
                e.errorCode
            }
        }

    private fun seed(
        authorId: Long,
        otherPost: Long,
    ) {
        jdbcTemplate.update(
            """
            insert into posts (author_id, author_job_role, author_career_year, content, comment_tone, created_at, updated_at)
            select ?, 'DEVELOPMENT', 'YEAR_3', '많은 글 ' || g, 'COMFORT_ME', now(), now()
            from generate_series(1, $POSTS) g
            """.trimIndent(),
            authorId,
        )
        jdbcTemplate.update(
            """
            insert into comments (post_id, author_id, content, created_at, updated_at)
            select ?, ?, '많은 댓글 ' || g, now(), now() from generate_series(1, $COMMENTS) g
            """.trimIndent(),
            otherPost,
            authorId,
        )
        jdbcTemplate.update("update posts set comment_count = ? where id = ?", COMMENTS, otherPost)
    }

    private companion object {
        const val CROSS_COMMENTS = 5
        const val POSTS = 1000
        const val COMMENTS = 5000
        const val WAIT_SECONDS = 10L
        const val BLOCK_MILLIS = 500L
        val RESPONSE_LIMIT: Duration = Duration.ofSeconds(3)
        val CLEANUP_LIMIT: Duration = Duration.ofMinutes(3)
    }
}
