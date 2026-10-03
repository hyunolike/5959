package com.ogu.monster

import com.ogu.TestcontainersConfiguration
import com.ogu.emotion.EmotionAnalyzed
import com.ogu.emotion.EmotionType
import com.ogu.emotion.Intensity
import com.ogu.post.application.CommentService
import com.ogu.post.application.LikeService
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.MonsterDefeatedRecorder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * T038: 동시 공격(US3-AC7, FR-010, SC-004, research R4, R5). 공감과 댓글은 서비스를 여러 스레드에서 직접 부른다(회원 100명을
 * API로 가입시키면 느리므로 회원 행은 SQL로 만든다). 몬스터 생성과 공감의 경합은 테스트가 같은 글 잠금을 먼저 쥐고, 두
 * 트랜잭션이 그 잠금을 차례로 기다리는 것을 `pg_stat_activity`로 확인한 뒤 놓아 순서를 정한다(Postgres 잠금 대기열은
 * 먼저 온 순서다).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MonsterConcurrencyTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var likeService: LikeService

    @Autowired
    lateinit var commentService: CommentService

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var defeated: MonsterDefeatedRecorder

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture

    @BeforeEach
    fun setUp() {
        val mockMvc: MockMvc =
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
    }

    @Test
    fun `US3-AC7 서로 다른 회원 100명이 동시에 공감하면 HP가 정확히 줄고 기록이 정확한 개수다`() {
        val author = members.onboarded()
        val postId = loop.postWithMonster(author, intensity = "높음")
        val fans = insertMembers(100)

        runConcurrently(fans.map { fan -> { likeService.likePost(postId, fan) } })

        assertThat(loop.monster(postId)).isEqualTo(MonsterView(EmotionType.ANXIETY, 0, 30, MonsterStatus.DEFEATED))
        val logs = loop.hpLogs(postId)
        assertThat(logs).hasSize(100)
        assertThat(logs.map { it.memberId }).containsExactlyInAnyOrderElementsOf(fans)
        // 잠금으로 줄을 세웠으므로 기록을 순서대로 이으면 30에서 0까지 빈틈없이 이어진다
        assertThat(logs.first().hpBefore).isEqualTo(30)
        logs.zipWithNext().forEach { (prev, next) -> assertThat(next.hpBefore).isEqualTo(prev.hpAfter) }
        assertThat(logs.count { it.hpBefore > 0 }).isEqualTo(30)
        assertThat(jdbcTemplate.queryForObject("select like_count from posts where id = ?", Int::class.java, postId))
            .isEqualTo(100)
        assertThat(defeated.forPost(postId)).hasSize(1)
    }

    @Test
    fun `같은 회원이 댓글 두 개를 동시에 달면 HP는 3만 준다`() {
        val author = members.onboarded()
        val postId = loop.postWithMonster(author, intensity = "높음")
        val commenter = insertMembers(1).single()

        runConcurrently((1..2).map { n -> { commentService.write(postId, commenter, "동시에 단 댓글 $n", null) } })

        assertThat(loop.monster(postId)!!.hp).isEqualTo(27)
        assertThat(loop.hpLogs(postId)).hasSize(1)
        assertThat(jdbcTemplate.queryForObject("select comment_count from posts where id = ?", Int::class.java, postId))
            .isEqualTo(2)
    }

    @Test
    fun `같은 회원이 같은 글에 동시에 두 번 공감하면 하나만 저장되고 다른 하나는 409다`() {
        val author = members.onboarded()
        val postId = loop.postWithMonster(author)
        val fan = insertMembers(1).single()

        val failures = runConcurrently((1..2).map { { likeService.likePost(postId, fan) } }, allowFailures = true)

        assertThat(failures).hasSize(1)
        assertThat(failures.single()).hasMessage("이미 공감했습니다.")
        assertThat(jdbcTemplate.queryForObject("select like_count from posts where id = ?", Int::class.java, postId))
            .isEqualTo(1)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(9)
    }

    @Test
    fun `몬스터 생성과 공감이 동시에 와도 공감은 정확히 한 번 반영된다 - 공감이 먼저 잠금을 잡으면 소급 반영`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val fan = insertMembers(1).single()

        // 공감이 먼저 잠금을 잡아 몬스터가 없음을 보고, 생성은 그 커밋을 기다렸다가 공감을 소급 반영한다
        raceThroughPostLock(
            postId,
            first = { likeService.likePost(postId, fan) },
            second = { analysisFinished(postId) },
        )

        assertThat(loop.awaitMonster(postId).hp).isEqualTo(9)
        assertThat(loop.hpLogs(postId).map { it.memberId to it.retroactive }).containsExactly(fan to true)
    }

    @Test
    fun `몬스터 생성과 공감이 동시에 와도 공감은 정확히 한 번 반영된다 - 생성이 먼저 잠금을 잡으면 이어서 반영`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val fan = insertMembers(1).single()

        // 생성은 아직 커밋되지 않은 공감을 보지 못하고, 공감은 생성이 커밋한 몬스터를 찾아 HP를 줄인다
        raceThroughPostLock(
            postId,
            first = { analysisFinished(postId) },
            second = { likeService.likePost(postId, fan) },
        )

        loop.awaitMonster(postId)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(9)
        assertThat(loop.hpLogs(postId).map { it.memberId to it.retroactive }).containsExactly(fan to false)
    }

    /**
     * 테스트 트랜잭션이 글 잠금을 쥔 채 [first], [second]를 차례로 시작하고, 각각이 그 잠금을 기다리는 것을 확인한 뒤 놓는다.
     * 잠금 대기열은 먼저 온 순서라 [first]가 먼저 잠금을 잡는다. 둘 다 끝날 때까지 기다린다.
     */
    private fun raceThroughPostLock(
        postId: Long,
        first: () -> Unit,
        second: () -> Unit,
    ) {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = mutableListOf<Future<*>>()
            transactionTemplate.executeWithoutResult {
                jdbcTemplate.queryForList("select pg_advisory_xact_lock(?)", postId)
                futures += executor.submit(first)
                awaitPostLockWaiters(postId, 1)
                futures += executor.submit(second)
                awaitPostLockWaiters(postId, 2)
                assertThat(loop.monster(postId)).isNull()
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * 이 글의 잠금(`pg_advisory_xact_lock(postId)`)을 기다리는 백엔드가 [expected]개가 될 때까지 기다린다.
     * 테스트 트랜잭션 안에서 읽으므로 `pg_stat_activity` 스냅숏이 고정되지 않게 매번 `pg_stat_clear_snapshot()`을
     * 부른다(M1 교훈). bigint 키 잠금은 `pg_locks`에 상위 32비트가 classid, 하위 32비트가 objid로 보인다.
     */
    private fun awaitPostLockWaiters(
        postId: Long,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + LOCK_WAIT_LIMIT.toNanos()
        while (System.nanoTime() < deadline) {
            jdbcTemplate.queryForList("select pg_stat_clear_snapshot()")
            val waiting =
                jdbcTemplate.queryForObject(
                    """
                    select count(*) from pg_stat_activity a join pg_locks l on l.pid = a.pid
                    where a.datname = current_database() and a.wait_event_type = 'Lock' and a.wait_event = 'advisory'
                      and l.locktype = 'advisory' and not l.granted
                      and l.classid::bigint = (? >> 32) and l.objid::bigint = (? & 4294967295) and l.objsubid = 1
                    """.trimIndent(),
                    Int::class.java,
                    postId,
                    postId,
                )
            if (waiting == expected) return
            Thread.sleep(LOCK_POLL_MILLIS)
        }
        jdbcTemplate.queryForList("select pg_stat_clear_snapshot()")
        val activity =
            jdbcTemplate.queryForList(
                """
                select pid, state, wait_event_type, wait_event, left(query, 60) as query from pg_stat_activity
                where datname = current_database() and pid <> pg_backend_pid()
                """.trimIndent(),
            )
        error("글 잠금 대기자가 ${expected}개가 되지 않았다. pg_stat_activity: $activity")
    }

    /** 분석이 끝났다고 알리고(커밋) MonsterFactory가 비동기로 몬스터를 만들기 시작하게 한다. */
    private fun analysisFinished(postId: Long) {
        transactionTemplate.executeWithoutResult {
            events.publishEvent(EmotionAnalyzed(postId, EmotionType.ANXIETY, Intensity.LOW, defaulted = false))
        }
    }

    /** 모든 작업을 한꺼번에 출발시키고 끝날 때까지 기다린다. [allowFailures]면 실패한 작업의 예외를 돌려준다. */
    private fun runConcurrently(
        tasks: List<() -> Any?>,
        allowFailures: Boolean = false,
    ): List<Throwable> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(minOf(tasks.size, MAX_THREADS))
        try {
            val futures =
                tasks.map { task ->
                    executor.submit<Any?> {
                        start.await()
                        task()
                    }
                }
            start.countDown()
            val failures =
                futures.mapNotNull { future ->
                    runCatching { future.get(30, TimeUnit.SECONDS) }.exceptionOrNull()?.cause
                }
            if (!allowFailures) assertThat(failures).isEmpty()
            return failures
        } finally {
            executor.shutdownNow()
        }
    }

    /** 온보딩을 마친 회원 행을 SQL로 바로 만든다. 서비스를 직접 부르므로 토큰은 필요 없다. */
    private fun insertMembers(count: Int): List<Long> =
        (1..count).map {
            val key = UUID.randomUUID().toString().take(10)
            jdbcTemplate.queryForObject(
                """
                insert into member (auth_method, email, password_hash, nickname, nickname_key, job_role, career_year,
                                    onboarded_at, created_at, updated_at)
                values ('EMAIL', ?, 'x', ?, ?, 'DEVELOPMENT', 'YEAR_3', now(), now(), now())
                returning id
                """.trimIndent(),
                Long::class.java,
                "$key@example.com",
                key,
                key,
            )!!
        }

    private companion object {
        val LOCK_WAIT_LIMIT: Duration = Duration.ofSeconds(10)
        const val LOCK_POLL_MILLIS = 20L
        const val MAX_THREADS = 32
    }
}
