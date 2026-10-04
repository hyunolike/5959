package com.ogu.post

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.post.application.CommentService
import com.ogu.post.application.LikeService
import com.ogu.post.application.PostService
import com.ogu.post.domain.CommentRepository
import com.ogu.post.domain.PostRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.lock.PostLock
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 수정, 삭제와 동시에 들어온 다른 요청(US4, FR-013, FR-014). 순서는 테스트가 정한다.
 * - 수정이 공감 수와 댓글 수를 덮어쓰지 않는지: 테스트 트랜잭션이 엔티티를 읽어 둔 사이에 다른 트랜잭션이 공감과 댓글을
 *   커밋하고, 그 뒤에 수정하고 flush한다. 바뀐 열만 UPDATE해야(`@DynamicUpdate`) 카운터가 남는다.
 * - 삭제와 겹친 공감, 수정: 삭제가 행을 잠근 채 커밋 직전에 멈추게 하고([raceBehindDelete]), 그 사이 상대 요청이 앞선
 *   "살아 있음" 확인을 통과한 뒤 그 행을 기다리는 것을 `pg_stat_activity`로 확인하고 나서 삭제를 커밋시킨다. 상대 요청은
 *   지운 행을 보고 404가 된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PostManageRaceTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var postRepository: PostRepository

    @Autowired
    lateinit var commentRepository: CommentRepository

    @Autowired
    lateinit var postService: PostService

    @Autowired
    lateinit var commentService: CommentService

    @Autowired
    lateinit var likeService: LikeService

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
    }

    @Test
    fun `글을 읽어 둔 사이에 커밋된 공감과 댓글 수를 글 수정이 덮어쓰지 않는다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        transactionTemplate.executeWithoutResult {
            val post = postRepository.findById(postId).orElseThrow()
            inAnotherThread {
                likeService.likePost(postId, fan.id)
                commentService.write(postId, fan.id, "동시에 단 댓글", null)
            }
            post.edit("고친 본문", null, Instant.now())
            entityManager.flush()
        }

        val row = jdbcTemplate.queryForMap("select content, like_count, comment_count from posts where id = ?", postId)
        assertThat(row["content"]).isEqualTo("고친 본문")
        assertThat(row["like_count"]).isEqualTo(1)
        assertThat(row["comment_count"]).isEqualTo(1)
    }

    @Test
    fun `댓글을 읽어 둔 사이에 커밋된 댓글 공감 수를 댓글 수정이 덮어쓰지 않는다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(author, postId, "원래 댓글")

        transactionTemplate.executeWithoutResult {
            val comment = commentRepository.findById(commentId).orElseThrow()
            inAnotherThread { likeService.likeComment(commentId, fan.id) }
            comment.edit("고친 댓글", Instant.now())
            entityManager.flush()
        }

        val row = jdbcTemplate.queryForMap("select content, like_count from comments where id = ?", commentId)
        assertThat(row["content"]).isEqualTo("고친 댓글")
        assertThat(row["like_count"]).isEqualTo(1)
    }

    @Test
    fun `삭제가 먼저 커밋되면 겹친 글 공감은 404 POST_NOT_FOUND이고 공감도 HP도 남지 않는다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithMonster(author)

        val (deleted, liked) =
            raceBehindDelete(
                hold = PostLockHold(postId),
                delete = { postService.delete(postId, author.id) },
                other = { likeService.likePost(postId, fan.id) },
            )

        assertThat(deleted.isSuccess).isTrue()
        assertThat(errorCodeOf(liked)).isEqualTo(ErrorCode.POST_NOT_FOUND)
        assertThat(count("select count(*) from post_likes where post_id = ?", postId)).isZero()
        assertThat(count("select like_count from posts where id = ?", postId)).isZero()
        assertThat(loop.monster(postId)!!.hp).isEqualTo(10)
        assertThat(loop.hpLogs(postId)).isEmpty()
    }

    @Test
    fun `삭제가 먼저 커밋되면 겹친 댓글 공감은 404 COMMENT_NOT_FOUND이고 공감도 HP도 남지 않는다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithMonster(author)
        val commentId = loop.comment(commenter, postId, "지울 댓글")
        val hp = loop.monster(postId)!!.hp

        val (deleted, liked) =
            raceBehindDelete(
                hold = CommentCountHold(postId),
                delete = { commentService.delete(commentId, commenter.id) },
                other = { likeService.likeComment(commentId, fan.id) },
            )

        assertThat(deleted.isSuccess).isTrue()
        assertThat(errorCodeOf(liked)).isEqualTo(ErrorCode.COMMENT_NOT_FOUND)
        assertThat(count("select count(*) from comment_likes where comment_id = ?", commentId)).isZero()
        assertThat(count("select like_count from comments where id = ?", commentId)).isZero()
        assertThat(loop.monster(postId)!!.hp).isEqualTo(hp)
    }

    @Test
    fun `삭제가 먼저 커밋되면 겹친 글 수정은 404 POST_NOT_FOUND이고 본문은 그대로다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        val (deleted, edited) =
            raceBehindDelete(
                hold = PostLockHold(postId),
                delete = { postService.delete(postId, author.id) },
                other = { postService.update(postId, author.id, "지운 뒤 고친 본문", null) },
            )

        assertThat(deleted.isSuccess).isTrue()
        assertThat(errorCodeOf(edited)).isEqualTo(ErrorCode.POST_NOT_FOUND)
        val content = jdbcTemplate.queryForObject("select content from posts where id = ?", String::class.java, postId)
        assertThat(content).isEqualTo("[실패] 분석 중인 글")
    }

    @Test
    fun `삭제가 먼저 커밋되면 겹친 댓글 수정은 404 COMMENT_NOT_FOUND이고 본문은 그대로다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(author, postId, "원래 댓글")

        val (deleted, edited) =
            raceBehindDelete(
                hold = CommentCountHold(postId),
                delete = { commentService.delete(commentId, author.id) },
                other = { commentService.update(commentId, author.id, "지운 뒤 고친 댓글") },
            )

        assertThat(deleted.isSuccess).isTrue()
        assertThat(errorCodeOf(edited)).isEqualTo(ErrorCode.COMMENT_NOT_FOUND)
        val content =
            jdbcTemplate.queryForObject("select content from comments where id = ?", String::class.java, commentId)
        assertThat(content).isEqualTo("원래 댓글")
    }

    private fun errorCodeOf(result: Result<Unit>): ErrorCode? {
        val failure = result.exceptionOrNull() as? BusinessException
        return failure?.errorCode
    }

    private fun count(
        sql: String,
        id: Long,
    ): Int = jdbcTemplate.queryForObject(sql, Int::class.java, id)!!

    /** 다른 스레드(다른 트랜잭션)에서 [task]를 끝까지 돌린다. 그 트랜잭션은 돌아오기 전에 커밋된다. */
    private fun inAnotherThread(task: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit(task).get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * 삭제가 행을 잠그고 커밋 직전에 멈춘 사이에 [other]를 보내, [other]가 그 행을 기다리게 한 뒤 삭제를 커밋시킨다.
     * 행 잠금 대기열의 순서에 기대지 않는다(두 대기자가 같은 트랜잭션 ID를 기다리면 누가 먼저 깨어날지 정해져 있지 않다).
     * - 글 삭제는 posts 행을 UPDATE한 뒤 글 잠금을 잡으므로, 테스트가 글 잠금을 쥐고 있으면 행을 잠근 채 멈춘다.
     * - 댓글 삭제는 댓글 행을 UPDATE한 뒤 마지막에 posts 행(댓글 수)을 잠그므로, 테스트가 posts 행을 쥐고 있으면 멈춘다.
     * 두 결과(삭제, [other])를 돌려준다.
     */
    private fun raceBehindDelete(
        hold: Hold,
        delete: () -> Unit,
        other: () -> Unit,
    ): Pair<Result<Unit>, Result<Unit>> {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = mutableListOf<Future<*>>()
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.queryForList(hold.sql, *hold.params)
                futures += executor.submit(delete)
                awaitLockWaiter(hold.deleteWaitsOn)
                futures += executor.submit(other)
                awaitLockWaiter(hold.otherWaitsOn)
            }
            val results = futures.map { future -> runCatching { future.get(10, TimeUnit.SECONDS) }.map { } }
            return results[0].unwrapCause() to results[1].unwrapCause()
        } finally {
            executor.shutdownNow()
        }
    }

    /** 테스트 트랜잭션이 쥐는 잠금과, 그 동안 삭제와 상대 요청이 각각 기다리는 문장(정규식). */
    private sealed class Hold(
        val sql: String,
        val params: Array<Any>,
        val deleteWaitsOn: String,
        val otherWaitsOn: String,
    )

    private class PostLockHold(
        postId: Long,
    ) : Hold(
            "select pg_advisory_xact_lock(?, ?)",
            arrayOf(PostLock.NAMESPACE, PostLock.key(postId)),
            "pg_advisory_xact_lock",
            "\\mposts\\M",
        )

    private class CommentCountHold(
        postId: Long,
    ) : Hold(
            "select id from posts where id = ? for no key update",
            arrayOf(postId),
            "update posts set comment_count",
            "\\mcomments\\M",
        )

    private fun Result<Unit>.unwrapCause(): Result<Unit> {
        val failure = exceptionOrNull() ?: return this
        return Result.failure(failure.cause ?: failure)
    }

    /**
     * 잠금을 기다리는 백엔드 가운데 문장이 [queryPattern]에 맞는 것이 하나가 될 때까지 기다린다. 테스트 트랜잭션 안에서
     * 읽으므로 매번 `pg_stat_clear_snapshot()`을 부른다(M1 교훈).
     */
    private fun awaitLockWaiter(queryPattern: String) {
        val deadline = System.nanoTime() + LOCK_WAIT_LIMIT.toNanos()
        while (System.nanoTime() < deadline) {
            jdbcTemplate.queryForList("select pg_stat_clear_snapshot()")
            val waiting =
                jdbcTemplate.queryForObject(
                    """
                    select count(*) from pg_stat_activity
                    where datname = current_database() and pid <> pg_backend_pid()
                      and wait_event_type = 'Lock' and query ~* ?
                    """.trimIndent(),
                    Int::class.java,
                    queryPattern,
                )
            if (waiting == 1) return
            Thread.sleep(LOCK_POLL_MILLIS)
        }
        jdbcTemplate.queryForList("select pg_stat_clear_snapshot()")
        val activity =
            jdbcTemplate.queryForList(
                """
                select pid, state, wait_event_type, wait_event, left(query, 80) as query from pg_stat_activity
                where datname = current_database() and pid <> pg_backend_pid()
                """.trimIndent(),
            )
        error("'$queryPattern' 문장이 잠금을 기다리지 않는다. pg_stat_activity: $activity")
    }

    private companion object {
        val LOCK_WAIT_LIMIT: Duration = Duration.ofSeconds(10)
        const val LOCK_POLL_MILLIS = 20L
    }
}
