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

    /**
     * 안 읽은 알림 한 행을 SQL로 바로 넣는다(알림이 많이 필요할 때). 번호 카운터도 [seq] 이상으로 올려 그 뒤의 실제 알림이
     * 다음 번호를 받게 한다. 공감 묶음(`POST_LIKE`)은 멱등 키가 없다. 만든 시각은 [backdate]로, 읽음은 [markRead]로 바꾼다.
     */
    fun seed(
        receiverId: Long,
        postId: Long,
        seq: Long,
        type: String = "POST_COMMENT",
        actorId: Long? = null,
    ): Long {
        val created = Timestamp.from(Instant.now())
        val id =
            jdbcTemplate.queryForObject(
                """
                insert into notification (receiver_id, type, post_id, latest_actor_id, dedup_key, seq,
                                          created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """.trimIndent(),
                Long::class.java,
                receiverId,
                type,
                postId,
                actorId,
                if (type == "POST_LIKE") null else "SEED:$seq",
                seq,
                created,
                created,
            )!!
        jdbcTemplate.update(
            """
            insert into notification_sequence (member_id, last_seq) values (?, ?)
            on conflict (member_id) do update
              set last_seq = greatest(notification_sequence.last_seq, excluded.last_seq)
            """.trimIndent(),
            receiverId,
            seq,
        )
        return id
    }

    /** 알림을 [createdAt]에 만든 것으로 바꾼다(보관 기간 확인용). */
    fun backdate(
        notificationId: Long,
        createdAt: Instant,
    ): Long {
        val created = Timestamp.from(createdAt)
        jdbcTemplate.update(
            "update notification set created_at = ?, updated_at = ? where id = ?",
            created,
            created,
            notificationId,
        )
        return notificationId
    }

    fun markRead(notificationId: Long) {
        jdbcTemplate.update("update notification set read_at = now() where id = ?", notificationId)
    }

    /** 이 글에 관한 이벤트 가운데 알림 리스너로 간 발행이 모두 끝날 때까지 기다린다. */
    fun awaitListenersIdle(postId: Long) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { incompletePublications(postId) == 0 }
    }

    /** 이 글(`postId`)에 관한 이벤트 가운데 알림 리스너로 가서 아직 끝나지 않은 발행 수. 모든 알림 이벤트는 글 ID를 싣는다. */
    fun incompletePublications(postId: Long): Int = incompletePublications("postId", postId)

    /**
     * 알림 리스너로 가서 아직 끝나지 않은 발행 가운데 직렬화한 이벤트의 [field] 값이 [value]인 것의 수. 필드 순서와
     * 공백에 기대지 않도록 JSON으로 읽어 비교한다. 다른 테스트가 남긴 발행은 ID가 달라 섞이지 않는다.
     */
    fun incompletePublications(
        field: String,
        value: Long,
    ): Int =
        jdbcTemplate.queryForObject(
            """
            select count(*) from event_publication
            where listener_id like 'com.ogu.notification.application.NotificationEventListener.%'
              and completion_date is null
              and (serialized_event::jsonb ->> ?) = ?
            """.trimIndent(),
            Int::class.java,
            field,
            value.toString(),
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
