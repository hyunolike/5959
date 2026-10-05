package com.ogu.notification

import com.ogu.notification.stream.NotificationSignal
import org.awaitility.Awaitility.await
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 저장된 알림 한 행을 테스트에서 보기 쉽게 줄인 것. */
data class NotificationRow(
    val id: Long,
    val type: String,
    val postId: Long,
    val commentId: Long?,
    val monsterId: Long?,
    val latestActorId: Long?,
    val actorCount: Int,
    val dedupKey: String?,
    val seq: Long,
    val read: Boolean,
)

/**
 * 알림 테스트 도우미. 알림 행은 읽기 전용 SQL로 확인하고, 비동기 알림 리스너가 끝났는지는 Event Publication Registry의
 * 미완료 행으로 기다린다. 신호는 앱의 구독 컨테이너에 붙인 리스너로 실제 Redis 채널에서 모은다.
 */
class NotificationTestSupport(
    private val jdbcTemplate: JdbcTemplate,
    private val redis: StringRedisTemplate,
    private val container: RedisMessageListenerContainer,
) {
    private val since = Instant.now().minusSeconds(1)
    private val received = CopyOnWriteArrayList<String>()
    private val listener = MessageListener { message, _ -> received += String(message.body) }

    /** 받는 사람의 알림을 번호 순서로. */
    fun notificationsOf(receiverId: Long): List<NotificationRow> =
        jdbcTemplate.query(
            """
            select id, type, post_id, comment_id, monster_id, latest_actor_id, actor_count, dedup_key, seq, read_at
            from notification where receiver_id = ? order by seq
            """.trimIndent(),
            { rs, _ ->
                NotificationRow(
                    id = rs.getLong("id"),
                    type = rs.getString("type"),
                    postId = rs.getLong("post_id"),
                    commentId = rs.getObject("comment_id") as Long?,
                    monsterId = rs.getObject("monster_id") as Long?,
                    latestActorId = rs.getObject("latest_actor_id") as Long?,
                    actorCount = rs.getInt("actor_count"),
                    dedupKey = rs.getString("dedup_key"),
                    seq = rs.getLong("seq"),
                    read = rs.getTimestamp("read_at") != null,
                )
            },
            receiverId,
        )

    fun notificationsOf(
        receiverId: Long,
        vararg types: String,
    ): List<NotificationRow> = notificationsOf(receiverId).filter { it.type in types }

    fun lastSeq(memberId: Long): Long =
        jdbcTemplate
            .queryForList("select last_seq from notification_sequence where member_id = ?", Long::class.java, memberId)
            .firstOrNull() ?: 0L

    fun participants(postId: Long): List<Pair<Long, Long>> =
        jdbcTemplate.query(
            "select liker_id, notification_id from like_notification_participant where post_id = ? order by liker_id",
            { rs, _ -> rs.getLong("liker_id") to rs.getLong("notification_id") },
            postId,
        )

    fun markRead(notificationId: Long) {
        jdbcTemplate.update("update notification set read_at = now() where id = ?", notificationId)
    }

    /** 이 도우미를 만든 뒤 알림 리스너로 간 이벤트 발행이 모두 끝날 때까지 기다린다. */
    fun awaitListenersIdle() {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { incompletePublications() == 0 }
    }

    fun incompletePublications(): Int =
        jdbcTemplate.queryForObject(
            """
            select count(*) from event_publication
            where listener_id like '%NotificationEventListener%' and completion_date is null
              and publication_date >= ?
            """.trimIndent(),
            Int::class.java,
            Timestamp.from(since),
        )!!

    /** 알림 리스너로 간 이벤트 가운데 직렬화한 내용에 [fragment]가 들어 있고 아직 끝나지 않은 발행 수. */
    fun incompletePublicationsWith(fragment: String): Int =
        jdbcTemplate.queryForObject(
            """
            select count(*) from event_publication
            where listener_id like '%NotificationEventListener%' and completion_date is null
              and serialized_event like ?
            """.trimIndent(),
            Int::class.java,
            "%$fragment%",
        )!!

    /** 알림 채널 구독을 건다. 구독이 실제로 걸릴 때까지 표지를 보내 본다. */
    fun subscribeSignals() {
        container.addMessageListener(listener, ChannelTopic(NotificationSignal.CHANNEL))
        val probe = "probe-${UUID.randomUUID()}"
        await().atMost(AWAIT_LIMIT).until {
            redis.convertAndSend(NotificationSignal.CHANNEL, probe)
            received.contains(probe)
        }
        received.clear()
    }

    fun unsubscribeSignals() {
        container.removeMessageListener(listener)
    }

    /** 이 회원에게 온 새 알림 신호(`{memberId}:n:{seq}`). */
    fun newSignalsFor(memberId: Long): List<String> = received.filter { it.startsWith("$memberId:n:") }

    companion object {
        val AWAIT_LIMIT: Duration = Duration.ofSeconds(15)
        val POLL: Duration = Duration.ofMillis(50)
    }
}
