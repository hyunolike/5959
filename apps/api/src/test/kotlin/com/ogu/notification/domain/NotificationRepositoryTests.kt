package com.ogu.notification.domain

import com.ogu.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * T012: 알림 저장소. 번호 받기(research R4), 멱등 삽입(R6), 공감 묶음과 참여자(R7), 그리고 목록, 안 읽은 수, 재전송,
 * 읽음 처리가 모두 같은 보관 기간 조건([NotificationRetention])을 쓰는지 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class NotificationRepositoryTests {
    @Autowired
    lateinit var sequences: NotificationSequenceRepository

    @Autowired
    lateinit var notifications: NotificationRepository

    @Autowired
    lateinit var participants: LikeParticipantRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var clock: Clock

    @Test
    fun `번호는 회원마다 1부터 하나씩 오르고 다른 회원과 섞이지 않는다`() {
        val a = nextId()
        val b = nextId()

        assertThat(sequences.current(a)).isZero()
        assertThat(nextSeq(a)).isEqualTo(1L)
        assertThat(nextSeq(a)).isEqualTo(2L)
        assertThat(nextSeq(b)).isEqualTo(1L)
        assertThat(sequences.current(a)).isEqualTo(2L)
    }

    @Test
    fun `같은 받는 사람과 멱등 키의 알림은 한 번만 들어가고 두 번째는 null이다`() {
        val receiver = nextId()
        val first = notifications.insertIfAbsent(comment(receiver, commentId = 1L, seq = nextSeq(receiver)))
        val second = notifications.insertIfAbsent(comment(receiver, commentId = 1L, seq = nextSeq(receiver)))

        assertThat(first).isNotNull()
        assertThat(second).isNull()
        val saved = notifications.findPage(receiver, beforeSeq = null, size = 10)
        assertThat(saved).hasSize(1)
        assertThat(saved.single().dedupKey).isEqualTo("COMMENT:1")
        assertThat(saved.single().type).isEqualTo(NotificationType.POST_COMMENT)
    }

    @Test
    fun `안 읽은 공감 묶음이 있으면 인원, 최근 회원, 번호를 올리고 없으면 새로 만든다`() {
        val receiver = nextId()
        val postId = nextId()
        val now = now()

        val group = notifications.upsertLikeGroup(receiver, postId, actorId = 101L, seq = nextSeq(receiver), now)
        assertThat(participants.insertIfAbsent(postId, likerId = 101L, notificationId = group, now)).isTrue()
        val same = notifications.upsertLikeGroup(receiver, postId, actorId = 102L, seq = nextSeq(receiver), now)
        assertThat(participants.insertIfAbsent(postId, likerId = 102L, notificationId = same, now)).isTrue()

        assertThat(same).isEqualTo(group)
        val row = notifications.findPage(receiver, beforeSeq = null, size = 10).single()
        assertThat(row.actorCount).isEqualTo(2)
        assertThat(row.latestActorId).isEqualTo(102L)
        assertThat(row.seq).isEqualTo(2L)
        assertThat(row.dedupKey).isNull()

        // 읽은 뒤에 온 공감은 새 묶음이다
        notifications.markRead(group, receiver, now)
        val fresh = notifications.upsertLikeGroup(receiver, postId, actorId = 103L, seq = nextSeq(receiver), now)
        assertThat(fresh).isNotEqualTo(group)
    }

    @Test
    fun `같은 회원의 같은 글 공감 참여자는 한 번만 들어간다`() {
        val receiver = nextId()
        val postId = nextId()
        val now = now()
        val group = notifications.upsertLikeGroup(receiver, postId, actorId = 7L, seq = nextSeq(receiver), now)

        assertThat(participants.insertIfAbsent(postId, likerId = 7L, notificationId = group, now)).isTrue()
        assertThat(participants.insertIfAbsent(postId, likerId = 7L, notificationId = group, now)).isFalse()
    }

    @Test
    fun `참여자 키에 걸려 되돌리면 번호와 묶음 갱신도 함께 취소된다`() {
        val receiver = nextId()
        val postId = nextId()
        val now = now()
        val group = notifications.upsertLikeGroup(receiver, postId, actorId = 7L, seq = nextSeq(receiver), now)
        participants.insertIfAbsent(postId, likerId = 7L, notificationId = group, now)

        transactionTemplate.executeWithoutResult { status ->
            val id = notifications.upsertLikeGroup(receiver, postId, actorId = 7L, seq = sequences.next(receiver), now)
            if (!participants.insertIfAbsent(postId, likerId = 7L, notificationId = id, now)) {
                status.setRollbackOnly()
            }
        }

        assertThat(sequences.current(receiver)).isEqualTo(1L)
        assertThat(notifications.findPage(receiver, beforeSeq = null, size = 10).single().actorCount).isEqualTo(1)
    }

    @Test
    fun `목록, 안 읽은 수, 재전송, 읽음 처리는 보관 기간이 지난 알림을 보지 않는다`() {
        val receiver = nextId()
        val old = insertAged(receiver, age = Duration.ofDays(90).plusMinutes(1))
        val recent = insertAged(receiver, age = Duration.ofDays(89))

        assertThat(notifications.findPage(receiver, beforeSeq = null, size = 10).map { it.id }).containsExactly(recent)
        assertThat(notifications.countUnread(receiver)).isEqualTo(1L)
        assertThat(notifications.findAfterSeq(receiver, afterSeq = 0L, limit = 10).map { it.id })
            .containsExactly(recent)
        assertThat(notifications.markRead(old, receiver, now())).isEqualTo(ReadOutcome.NOT_FOUND)
        assertThat(notifications.markAllRead(receiver, upToSeq = Long.MAX_VALUE, now())).isEqualTo(1)
        assertThat(readAt(old)).isNull()
        assertThat(readAt(recent)).isNotNull()
    }

    @Test
    fun `하나 읽음은 처음이면 MARKED, 다시 하면 ALREADY_READ, 남의 알림이면 NOT_FOUND다`() {
        val receiver = nextId()
        val id = notifications.insertIfAbsent(comment(receiver, commentId = 5L, seq = nextSeq(receiver)))!!

        assertThat(notifications.markRead(id, receiver + 1, now())).isEqualTo(ReadOutcome.NOT_FOUND)
        assertThat(readAt(id)).isNull()
        assertThat(notifications.markRead(id, receiver, now())).isEqualTo(ReadOutcome.MARKED)
        assertThat(notifications.markRead(id, receiver, now())).isEqualTo(ReadOutcome.ALREADY_READ)
        assertThat(notifications.countUnread(receiver)).isZero()
    }

    @Test
    fun `모두 읽음은 upToSeq 이하만 읽음으로 바꾼다`() {
        val receiver = nextId()
        repeat(3) {
            notifications.insertIfAbsent(comment(receiver, commentId = it + 10L, seq = nextSeq(receiver)))
        }

        assertThat(notifications.markAllRead(receiver, upToSeq = 2L, now())).isEqualTo(2)
        assertThat(notifications.countUnread(receiver)).isEqualTo(1L)
    }

    @Test
    fun `목록은 seq 내림차순 키셋이고 재전송은 seq 오름차순이다`() {
        val receiver = nextId()
        repeat(5) {
            notifications.insertIfAbsent(comment(receiver, commentId = it + 20L, seq = nextSeq(receiver)))
        }

        assertThat(notifications.findPage(receiver, beforeSeq = null, size = 2).map { it.seq }).containsExactly(5L, 4L)
        assertThat(notifications.findPage(receiver, beforeSeq = 4L, size = 2).map { it.seq }).containsExactly(3L, 2L)
        assertThat(notifications.findAfterSeq(receiver, afterSeq = 2L, limit = 10).map { it.seq })
            .containsExactly(3L, 4L, 5L)
        assertThat(notifications.findAfterSeq(receiver, afterSeq = 2L, limit = 2).map { it.seq })
            .containsExactly(3L, 4L)
    }

    @Test
    fun `번호 받기는 트랜잭션 밖에서 부르면 거부된다(커밋 순서와 번호 순서를 같게 하는 행 잠금이 커밋까지 남아야 한다)`() {
        val member = nextId()

        // @Repository의 예외 변환이 IllegalStateException을 InvalidDataAccessApiUsageException으로 감싼다
        assertThatThrownBy { sequences.next(member) }.hasMessageContaining("트랜잭션 안에서만")
        assertThat(sequences.current(member)).isZero()
        assertThat(nextSeq(member)).isEqualTo(1L)
    }

    @Test
    fun `보관 기간이 지났지만 아직 지우지 않은 안 읽은 묶음이 있어도 새 공감은 보이는 새 묶음을 만든다`() {
        val receiver = nextId()
        val postId = nextId()
        val expiredAt = now().minus(Duration.ofDays(90).plusMinutes(1))
        val expired = notifications.upsertLikeGroup(receiver, postId, actorId = 7L, seq = nextSeq(receiver), expiredAt)
        participants.insertIfAbsent(postId, likerId = 7L, notificationId = expired, expiredAt)
        val lastDelivered = sequences.current(receiver)

        val fresh =
            transactionTemplate.execute {
                notifications.upsertLikeGroup(receiver, postId, actorId = 8L, seq = sequences.next(receiver), now())
            }!!

        assertThat(fresh).isNotEqualTo(expired)
        val visible = notifications.findPage(receiver, beforeSeq = null, size = 10).single()
        assertThat(visible.id).isEqualTo(fresh)
        assertThat(visible.actorCount).isEqualTo(1)
        assertThat(visible.latestActorId).isEqualTo(8L)
        assertThat(notifications.findAfterSeq(receiver, afterSeq = lastDelivered, limit = 10).map { it.id })
            .containsExactly(fresh)
        assertThat(notifications.countUnread(receiver)).isEqualTo(1L)
        // 지난 묶음은 그대로 보관 기간 밖에 남아 정리 작업이 지운다
        val old =
            jdbcTemplate.queryForMap("select actor_count, read_at from notification where id = ?", expired)
        assertThat(old["actor_count"]).isEqualTo(1)
        assertThat(old["read_at"]).isNotNull()
    }

    private fun nextSeq(member: Long): Long = transactionTemplate.execute { sequences.next(member) }!!

    private fun comment(
        receiver: Long,
        commentId: Long,
        seq: Long,
    ) = NewNotification(
        receiverId = receiver,
        type = NotificationType.POST_COMMENT,
        postId = 1L,
        commentId = commentId,
        monsterId = null,
        actorId = 99L,
        dedupKey = "COMMENT:$commentId",
        seq = seq,
        createdAt = now(),
    )

    private fun insertAged(
        receiver: Long,
        age: Duration,
    ): Long {
        val createdAt = now().minus(age)
        return notifications.insertIfAbsent(
            comment(receiver, commentId = nextId(), seq = nextSeq(receiver)).copy(createdAt = createdAt),
        )!!
    }

    private fun readAt(id: Long): Timestamp? =
        jdbcTemplate.queryForObject("select read_at from notification where id = ?", Timestamp::class.java, id)

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun nextId(): Long = IDS.incrementAndGet()

    companion object {
        private val IDS = AtomicLong(8_000_000_000L + System.nanoTime() % 1_000_000_000L)
    }
}
