package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.notification.application.NotificationPurgeJob
import com.ogu.notification.domain.NotificationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * T040: 보관 기간 정리 작업(FR-010, research R10). 정리 작업은 표 전체를 보므로(다른 테스트가 남긴 오래된 행도 지운다)
 * 결과는 이 테스트가 넣은 받는 사람과 발행 ID로만 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
// 저장소를 감싼 이 클래스만의 컨텍스트다. 끝나면 닫아 컨테이너와 메모리를 돌려준다
@DirtiesContext
class NotificationPurgeJobTest {
    @Autowired
    lateinit var job: NotificationPurgeJob

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var redis: StringRedisTemplate

    @Autowired
    lateinit var container: RedisMessageListenerContainer

    @MockitoSpyBean
    lateinit var notifications: NotificationRepository

    private val unusedIds = AtomicLong(System.nanoTime() % UNUSED_ID_RANGE)
    private lateinit var support: NotificationTestSupport

    @BeforeEach
    fun setUp() {
        support = NotificationTestSupport(jdbcTemplate, redis, container)
    }

    @Test
    fun `만든 지 90일이 지난 알림을 1,000행씩 지운다`() {
        val receiver = unusedId()
        jdbcTemplate.update(
            """
            insert into notification (receiver_id, type, post_id, dedup_key, seq, created_at, updated_at)
            select ?, 'POST_COMMENT', 1, 'SEED:' || g, g, now() - interval '91 days', now() - interval '91 days'
            from generate_series(1, ?) g
            """.trimIndent(),
            receiver,
            EXPIRED_ROWS,
        )
        val boundary = Instant.now().minus(RETENTION)
        val justExpired = seedAt(receiver, seq = EXPIRED_ROWS + 1L, createdAt = boundary.minusSeconds(1))
        val stillKept = seedAt(receiver, seq = EXPIRED_ROWS + 2L, createdAt = boundary.plusSeconds(60))
        val fresh = support.seed(receiver, unusedId(), seq = EXPIRED_ROWS + 3L)
        clearInvocations(notifications)

        val result = job.purge()

        assertThat(support.notificationsOf(receiver).map { it.id }).containsExactly(stillKept, fresh)
        assertThat(support.notificationsOf(receiver).map { it.id }).doesNotContain(justExpired)
        // 한 문장이 1,000행까지만 지운다. 2,501행이면 세 번 넘게 돈다
        assertThat(result.notifications).isGreaterThanOrEqualTo(EXPIRED_ROWS + 1)
        assertThat(result.batches).isGreaterThanOrEqualTo(EXPIRED_BATCHES)
        verify(notifications, atLeast(EXPIRED_BATCHES)).deleteExpired(BATCH_SIZE)
    }

    @Test
    fun `최근에 갱신된 묶음도 만든 시각 기준으로 지우고 참여자 행이 CASCADE로 지워진다`() {
        val receiver = unusedId()
        val oldPost = unusedId()
        val newPost = unusedId()
        val created = Instant.now().minus(RETENTION).minus(Duration.ofDays(1))
        val oldGroup = support.backdate(support.seed(receiver, oldPost, seq = 1, type = "POST_LIKE"), created)
        // 어제 공감이 더해져 갱신됐지만 보관 기간은 만든 시각으로 센다
        jdbcTemplate.update("update notification set updated_at = now() - interval '1 day' where id = ?", oldGroup)
        val keptGroup = support.seed(receiver, newPost, seq = 2, type = "POST_LIKE")
        listOf(oldPost to oldGroup, oldPost to oldGroup, newPost to keptGroup).forEach { (post, group) ->
            jdbcTemplate.update(
                """
                insert into like_notification_participant (post_id, liker_id, notification_id, created_at)
                values (?, ?, ?, now())
                """.trimIndent(),
                post,
                unusedId(),
                group,
            )
        }
        assertThat(support.participants(oldPost)).hasSize(2)

        job.purge()

        assertThat(support.notificationsOf(receiver).map { it.id }).containsExactly(keptGroup)
        assertThat(support.participants(oldPost)).isEmpty()
        assertThat(support.participants(newPost).map { it.second }).containsExactly(keptGroup)
    }

    @Test
    fun `7일이 지난 완료된 이벤트 발행을 지운다`() {
        val now = Instant.now()
        val completedLongAgo = publication(completedAt = now.minus(Duration.ofDays(8)))
        val completedRecently = publication(completedAt = now.minus(Duration.ofDays(6)))
        // 끝나지 않은 발행은 아무리 오래돼도 지우지 않는다(재전송 대상이다). 재전송이 건드리지 않게 상한을 넘겨 둔다
        val incomplete = publication(completedAt = null)

        try {
            job.purge()

            assertThat(publicationExists(completedLongAgo)).isFalse()
            assertThat(publicationExists(completedRecently)).isTrue()
            assertThat(publicationExists(incomplete)).isTrue()
        } finally {
            jdbcTemplate.update(
                "delete from event_publication where id in (?, ?, ?)",
                completedLongAgo,
                completedRecently,
                incomplete,
            )
        }
    }

    @Test
    fun `두 번 실행해도 결과가 같다`() {
        val receiver = unusedId()
        val expired = Instant.now().minus(RETENTION).minusSeconds(1)
        (1L..3L).forEach { seedAt(receiver, seq = it, createdAt = expired) }
        val kept = support.seed(receiver, unusedId(), seq = 4)
        val completed = publication(completedAt = Instant.now().minus(Duration.ofDays(8)))

        val first = job.purge()
        val afterFirst = support.notificationsOf(receiver)
        val second = job.purge()

        assertThat(first.notifications).isGreaterThanOrEqualTo(3)
        assertThat(afterFirst.map { it.id }).containsExactly(kept)
        // 지울 것이 없으면 한 번 확인하고 끝난다
        assertThat(second.notifications).isZero()
        assertThat(second.batches).isEqualTo(1)
        assertThat(support.notificationsOf(receiver)).isEqualTo(afterFirst)
        assertThat(publicationExists(completed)).isFalse()
        assertThat(support.lastSeq(receiver)).isEqualTo(4L)
    }

    @Test
    fun `매일 04시 한국 시간에 돈다`() {
        val scheduled =
            NotificationPurgeJob::class.java.methods
                .mapNotNull { it.getAnnotation(Scheduled::class.java) }
                .single()

        assertThat(scheduled.cron).isEqualTo("\${ogu.notification.purge-cron:0 0 4 * * *}")
        assertThat(scheduled.zone).isEqualTo("Asia/Seoul")
    }

    private fun seedAt(
        receiver: Long,
        seq: Long,
        createdAt: Instant,
    ): Long = support.backdate(support.seed(receiver, unusedId(), seq), createdAt)

    private fun publication(completedAt: Instant?): UUID {
        val id = UUID.randomUUID()
        val published = Timestamp.from(Instant.now().minus(Duration.ofDays(30)))
        jdbcTemplate.update(
            """
            insert into event_publication (id, listener_id, event_type, serialized_event, publication_date,
                                           completion_date, status, completion_attempts)
            values (?, 'com.ogu.test.PurgeJobTest.on(java.lang.Object)', 'java.lang.Object', ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            """{"purgeTest":"$id"}""",
            published,
            completedAt?.let(Timestamp::from),
            if (completedAt == null) "FAILED" else "COMPLETED",
            GIVEN_UP_ATTEMPTS,
        )
        return id
    }

    private fun publicationExists(id: UUID): Boolean =
        jdbcTemplate.queryForObject("select count(*) from event_publication where id = ?", Int::class.java, id) == 1

    /** 어떤 회원이나 글도 가리키지 않는 ID. 알림 모듈은 회원과 글 표를 직접 보지 않아 이런 값도 저장된다. */
    private fun unusedId(): Long = UNUSED_ID_BASE + unusedIds.incrementAndGet()

    private companion object {
        const val EXPIRED_ROWS = 2_500
        const val BATCH_SIZE = 1_000
        const val EXPIRED_BATCHES = 3
        const val GIVEN_UP_ATTEMPTS = 99
        const val UNUSED_ID_BASE = 9_000_000_000L
        const val UNUSED_ID_RANGE = 1_000_000_000L
        val RETENTION: Duration = Duration.ofDays(90)
    }
}
