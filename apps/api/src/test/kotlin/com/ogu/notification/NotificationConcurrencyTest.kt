package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.notification.application.NotificationDraft
import com.ogu.notification.application.NotificationWriter
import com.ogu.notification.domain.NotificationRepository
import com.ogu.notification.domain.NotificationType
import com.ogu.notification.stream.NotificationSignal
import com.ogu.notification.stream.RedisSignalPublisher
import com.ogu.notification.stream.SseHub
import com.ogu.post.application.LikeService
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SseStream
import com.ogu.support.SseTestClient
import com.ogu.support.StreamTickets
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.random.Random

/**
 * T024: 알림 동시성(SC-005, research R4, R5, R7).
 *
 * - 공감 100건이 한 묶음으로 모이는지는 공감 서비스를 여러 스레드에서 직접 부른다(회원 행은 SQL로 만든다).
 * - 번호 순서와 교착은 [NotificationWriter]를 여러 트랜잭션에서 직접 부른다. 커밋 순서가 번호 순서와 같은지는 커밋된
 *   번호를 계속 읽어 언제나 1부터 빈칸 없이 이어지는지로 본다(번호를 받고 커밋하기까지 일부러 조금씩 쉰다).
 * - 연결하는 순간의 경합(재전송 쿼리와 실시간 구독 사이)은 재전송 쿼리가 끝난 바로 그때 알림을 커밋하고 그 신호를 허브에
 *   보낸다. Redis 신호가 늦게 와서 구해 주는 일이 없게 발행기는 가짜로 바꾼다. 이 테스트들은 `pg_stat_activity`를 읽지
 *   않으므로 `pg_stat_clear_snapshot()`이 필요 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
// 이 클래스만의 설정이라 다른 테스트와 컨텍스트를 나누지 않는다. 끝나면 닫아 컨테이너와 메모리를 돌려준다
@DirtiesContext
class NotificationConcurrencyTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var likeService: LikeService

    @Autowired
    lateinit var writer: NotificationWriter

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var hub: SseHub

    @MockitoBean
    lateinit var signals: RedisSignalPublisher

    @MockitoSpyBean
    lateinit var notificationRepository: NotificationRepository

    @LocalServerPort
    var port: Int = 0

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var tickets: StreamTickets
    private val streams = mutableListOf<SseStream>()

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        tickets = StreamTickets(mockMvc)
    }

    @AfterEach
    fun tearDown() {
        streams.forEach(SseStream::close)
    }

    @Test
    fun `US1-AC2 서로 다른 회원 100명이 같은 글에 동시에 공감하면 안 읽은 공감 묶음이 하나이고 actor_count가 100이다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val fans = insertMembers(FANS)

        runConcurrently(fans.map { fan -> { likeService.likePost(postId, fan) } })

        await().atMost(AWAIT).pollInterval(POLL).until { unreadLikeGroups(author.id).singleOrNull()?.second == FANS }
        await().atMost(AWAIT).pollInterval(POLL).until { participantCount(postId) == FANS }
        val groups = unreadLikeGroups(author.id)
        assertThat(groups).hasSize(1)
        assertThat(groups.single().second).isEqualTo(FANS)
        assertThat(countOf(author.id)).isEqualTo(1)
    }

    @Test
    fun `회원의 seq가 겹치지 않고 커밋 순서와 같다`() {
        val receiver = insertMembers(1).single()
        val actor = insertMembers(1).single()
        val violations = CopyOnWriteArrayList<List<Long>>()
        val done = AtomicBoolean()
        // 쓰는 쪽과 풀을 다투지 않게 지켜보는 쪽은 연결 하나를 따로 쥐고 쉬지 않고 읽는다
        val watcherConnection = dataSource.connection
        val watcher =
            Thread.ofPlatform().start {
                watcherConnection.use { connection ->
                    while (!done.get()) {
                        val committed = committedSeqs(connection, receiver)
                        if (committed != (1L..committed.size.toLong()).toList()) violations += committed
                    }
                }
            }

        runConcurrently(List(WRITES) { i -> slowCommentWrite(receiver, actor, commentId = i + 1L) })
        done.set(true)
        watcher.join()

        assertThat(violations).isEmpty()
        assertThat(seqsOf(receiver)).isEqualTo((1L..WRITES).toList())
    }

    @Test
    fun `처치 알림(여러 받는 사람)과 공감 알림이 같은 회원에게 동시에 만들어져도 교착이 없다`() {
        val receivers = insertMembers(RECEIVERS).sorted()
        val likers = insertMembers(ROUNDS)
        val postId = Random.nextLong(1, Long.MAX_VALUE / 2)
        val tasks =
            (0 until ROUNDS).flatMap { round ->
                val monsterId = postId + round
                val order = if (round % 2 == 0) receivers else receivers.reversed()
                listOf(
                    {
                        transactionTemplate.executeWithoutResult {
                            writer.writeAll(
                                order.map {
                                    NotificationDraft.defeated(
                                        NotificationType.MONSTER_DEFEATED_TOGETHER,
                                        it,
                                        postId,
                                        monsterId,
                                    )
                                },
                            )
                        }
                    },
                    {
                        transactionTemplate.executeWithoutResult {
                            writer.addLike(receivers[round % RECEIVERS], postId, likers[round])
                        }
                    },
                )
            }

        val failures = runConcurrently(tasks, allowFailures = true)

        assertThat(failures).isEmpty()
        receivers.forEach { receiver ->
            val seqs = seqsOf(receiver)
            assertThat(seqs).doesNotHaveDuplicates()
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from notification where receiver_id = ? and type = 'MONSTER_DEFEATED_TOGETHER'",
                    Int::class.java,
                    receiver,
                ),
            ).isEqualTo(ROUNDS)
        }
    }

    @Test
    fun `재전송 쿼리와 실시간 구독 사이에 커밋된 알림도 빠지지 않는다`() {
        val member = members.onboarded()
        val actor = insertMembers(1).single()
        val postId = Random.nextLong(1, Long.MAX_VALUE / 2)
        val commentIds = AtomicLong(postId)
        repeat(2) { write(commentDraft(member.id, actor, commentIds.incrementAndGet(), postId)) }
        val fired = AtomicBoolean()
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            // 첫 재전송 쿼리가 돌아온 바로 그때 새 알림이 커밋되고 그 신호가 허브에 닿는다
            if (invocation.getArgument<Long>(0) == member.id && fired.compareAndSet(false, true)) {
                write(commentDraft(member.id, actor, commentIds.incrementAndGet(), postId))
                hub.onSignal(NotificationSignal.New(member.id, seqsOf(member.id).last()))
            }
            result
        }.`when`(notificationRepository).findAfterSeq(anyLong(), anyLong(), anyInt())

        val stream = SseTestClient.connect(port, tickets.issue(member), lastEventId = 0).also { streams += it }

        // 안전망 주기(60초)보다 훨씬 짧게 기다린다. 신호로 따라잡지 못하면 여기서 실패한다
        val received = stream.awaitNotifications(3, Duration.ofSeconds(GAP_WAIT_SECONDS))
        assertThat(fired).isTrue()
        assertThat(received.map { it.id!!.toLong() }).containsExactly(1L, 2L, 3L)
    }

    /** 번호를 받고 커밋하기까지 제각각 쉰다. 그래야 늦게 받은 번호가 먼저 커밋될 기회가 생긴다. */
    private fun slowCommentWrite(
        receiver: Long,
        actor: Long,
        commentId: Long,
    ): () -> Unit =
        {
            transactionTemplate.executeWithoutResult {
                writer.writeAll(listOf(commentDraft(receiver, actor, commentId)))
                Thread.sleep(Random.nextLong(MAX_HOLD_MILLIS))
            }
        }

    private fun write(draft: NotificationDraft) {
        transactionTemplate.executeWithoutResult { writer.writeAll(listOf(draft)) }
    }

    private fun commentDraft(
        receiver: Long,
        actor: Long,
        commentId: Long,
        postId: Long = POST_ID,
    ): NotificationDraft = NotificationDraft.comment(NotificationType.POST_COMMENT, receiver, postId, commentId, actor)

    private fun committedSeqs(
        connection: Connection,
        receiver: Long,
    ): List<Long> {
        val sql = "select seq from notification where receiver_id = ? order by seq"
        return connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, receiver)
            statement.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getLong(1) else null }.toList()
            }
        }
    }

    private fun seqsOf(receiver: Long): List<Long> =
        jdbcTemplate.queryForList(
            "select seq from notification where receiver_id = ? order by seq",
            Long::class.java,
            receiver,
        )

    private fun countOf(
        receiver: Long,
        type: String? = null,
    ): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from notification where receiver_id = ? and (?::text is null or type = ?)",
            Int::class.java,
            receiver,
            type,
            type,
        )!!

    private fun unreadLikeGroups(receiver: Long): List<Pair<Long, Int>> =
        jdbcTemplate.query(
            "select id, actor_count from notification where receiver_id = ? and type = 'POST_LIKE' and read_at is null",
            { rs, _ -> rs.getLong("id") to rs.getInt("actor_count") },
            receiver,
        )

    private fun participantCount(postId: Long): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from like_notification_participant where post_id = ?",
            Int::class.java,
            postId,
        )!!

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
                    runCatching { future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                        .exceptionOrNull()
                        ?.let { it.cause ?: it }
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
        const val FANS = 100
        const val WRITES = 200
        const val MAX_HOLD_MILLIS = 20L
        const val RECEIVERS = 3
        const val ROUNDS = 30
        const val MAX_THREADS = 32
        const val TASK_TIMEOUT_SECONDS = 60L
        const val GAP_WAIT_SECONDS = 5L
        const val POST_ID = 1L
        val AWAIT: Duration = Duration.ofSeconds(30)
        val POLL: Duration = Duration.ofMillis(50)
    }
}
