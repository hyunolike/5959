package com.ogu.notification.domain

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * 알림 `notification` 저장소. 읽는 쿼리와 읽음 처리는 모두 [NotificationRetention.condition]으로 보관 기간 안의 알림만 본다.
 * 번호(`seq`)는 쓰기 전에 [NotificationSequenceRepository.next]로 받는다.
 */
@Repository
class NotificationRepository(
    private val jdbcClient: JdbcClient,
    private val retention: NotificationRetention,
) {
    /**
     * 멱등 키로 한 번만 넣는다(research R6). 같은 받는 사람과 키가 이미 있으면 아무것도 하지 않고 null이다. 그때는 신호도
     * 보내지 않는다.
     */
    fun insertIfAbsent(notification: NewNotification): Long? =
        jdbcClient
            .sql(
                """
                insert into notification (receiver_id, type, post_id, comment_id, monster_id, latest_actor_id,
                                          dedup_key, seq, created_at, updated_at)
                values (:receiverId, :type, :postId, :commentId, :monsterId, :actorId,
                        :dedupKey, :seq, :createdAt, :createdAt)
                on conflict on constraint notification_receiver_dedup_key do nothing
                returning id
                """.trimIndent(),
            ).param("receiverId", notification.receiverId)
            .param("type", notification.type.name)
            .param("postId", notification.postId)
            .param("commentId", notification.commentId)
            .param("monsterId", notification.monsterId)
            .param("actorId", notification.actorId)
            .param("dedupKey", notification.dedupKey)
            .param("seq", notification.seq)
            .param("createdAt", Timestamp.from(notification.createdAt))
            .query(Long::class.java)
            .optional()
            .orElse(null)

    /**
     * 이 받는 사람에게 같은 멱등 키의 알림이 이미 있는가. 보관 기간과 상관없이 본다(유일 키도 그렇다). 이미 있으면 번호를
     * 받지 않고 건너뛰어, 같은 이벤트가 다시 와도 번호에 빈칸이 생기지 않게 한다. 겹친 처리는 [insertIfAbsent]가 막는다.
     */
    fun existsByDedupKey(
        receiverId: Long,
        dedupKey: String,
    ): Boolean =
        jdbcClient
            .sql(
                "select exists (select 1 from notification where receiver_id = :receiverId and dedup_key = :dedupKey)",
            ).param("receiverId", receiverId)
            .param("dedupKey", dedupKey)
            .query(Boolean::class.java)
            .single()

    /**
     * 안 읽은 공감 묶음에 공감 하나를 더하고 그 묶음 ID를 돌려준다(research R7). 묶음이 있으면 인원을 하나 올리고 최근 회원,
     * 번호, 갱신 시각을 바꾼다. 없으면 인원 1로 새로 만든다. `ON CONFLICT`는 부분 유일 인덱스
     * `notification_unread_like_group_key`의 조건식을 그대로 되풀이한다. 다음에는 그 ID로 참여자를 넣는다.
     *
     * 보관 기간이 지났지만 정리 작업이 아직 지우지 않은 안 읽은 묶음은 부분 유일 인덱스를 계속 차지한다. 그대로 두면 새 공감이
     * 보이지 않는 묶음에 더해지고 곧 함께 지워진다. 그래서 같은 트랜잭션에서 그 묶음을 먼저 읽음으로 돌려 자리를 비운다.
     * 이미 화면에서 빠진 알림이라 받는 사람이 보는 것은 바뀌지 않는다.
     *
     * 참여자 키에 걸리면 호출한 쪽이 이 갱신까지 되돌려야 하므로 트랜잭션 안에서만 부를 수 있다.
     */
    fun upsertLikeGroup(
        receiverId: Long,
        postId: Long,
        actorId: Long,
        seq: Long,
        now: Instant,
    ): Long {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "공감 묶음은 번호를 받은 트랜잭션 안에서만 고친다"
        }
        jdbcClient
            .sql(
                """
                update notification n set read_at = :now
                where n.receiver_id = :receiverId and n.post_id = :postId and n.type = 'POST_LIKE'
                  and n.read_at is null and not (${NotificationRetention.condition("n")})
                """.trimIndent(),
            ).param("now", Timestamp.from(now))
            .param("receiverId", receiverId)
            .param("postId", postId)
            .params(retention.params())
            .update()
        return jdbcClient
            .sql(
                """
                insert into notification (receiver_id, type, post_id, latest_actor_id, seq, created_at, updated_at)
                values (:receiverId, 'POST_LIKE', :postId, :actorId, :seq, :now, :now)
                on conflict (receiver_id, post_id) where type = 'POST_LIKE' and read_at is null
                do update set actor_count = notification.actor_count + 1,
                              latest_actor_id = excluded.latest_actor_id,
                              seq = excluded.seq,
                              updated_at = excluded.updated_at
                returning id
                """.trimIndent(),
            ).param("receiverId", receiverId)
            .param("postId", postId)
            .param("actorId", actorId)
            .param("seq", seq)
            .param("now", Timestamp.from(now))
            .query(Long::class.java)
            .single()
    }

    /** 목록 한 쪽. `seq` 내림차순 키셋이고 [beforeSeq]가 있으면 그보다 작은 번호만 본다. */
    fun findPage(
        receiverId: Long,
        beforeSeq: Long?,
        size: Int,
    ): List<Notification> {
        val keyset = if (beforeSeq == null) "" else "and n.seq < :beforeSeq"
        return jdbcClient
            .sql(
                """
                select n.* from notification n
                where n.receiver_id = :receiverId and ${NotificationRetention.condition("n")} $keyset
                order by n.seq desc
                limit :size
                """.trimIndent(),
            ).param("receiverId", receiverId)
            .params(retention.params())
            .apply { if (beforeSeq != null) param("beforeSeq", beforeSeq) }
            .param("size", size)
            .query(ROW_MAPPER)
            .list()
    }

    /** 재전송(research R4). [afterSeq]보다 큰 번호를 오름차순으로 본다. 묶음은 마지막 상태로 한 번만 나온다. */
    fun findAfterSeq(
        receiverId: Long,
        afterSeq: Long,
        limit: Int,
    ): List<Notification> =
        jdbcClient
            .sql(
                """
                select n.* from notification n
                where n.receiver_id = :receiverId and n.seq > :afterSeq and ${NotificationRetention.condition("n")}
                order by n.seq
                limit :limit
                """.trimIndent(),
            ).param("receiverId", receiverId)
            .param("afterSeq", afterSeq)
            .params(retention.params())
            .param("limit", limit)
            .query(ROW_MAPPER)
            .list()

    /**
     * 회원마다 가장 큰 알림 번호(research R5 안전망). 알림이 없는 회원은 결과에서 빠진다. 유일 키 `(receiver_id, seq)`의
     * 인덱스 끝만 읽는다.
     */
    fun maxSeqByReceivers(receiverIds: Collection<Long>): Map<Long, Long> {
        if (receiverIds.isEmpty()) return emptyMap()
        return jdbcClient
            .sql(
                """
                select receiver_id, max(seq) as max_seq from notification
                where receiver_id in (:ids)
                group by receiver_id
                """.trimIndent(),
            ).param("ids", receiverIds.toSet())
            .query { rs, _ -> rs.getLong("receiver_id") to rs.getLong("max_seq") }
            .list()
            .toMap()
    }

    /** 안 읽은 수(research R11). 부분 인덱스 `notification_unread_idx`를 쓴다. */
    fun countUnread(receiverId: Long): Long =
        jdbcClient
            .sql(
                """
                select count(*) from notification n
                where n.receiver_id = :receiverId and n.read_at is null and ${NotificationRetention.condition("n")}
                """.trimIndent(),
            ).param("receiverId", receiverId)
            .params(retention.params())
            .query(Long::class.java)
            .single()

    /** 하나 읽음(research R11). 남의 알림이나 보관 기간이 지난 알림은 존재 여부를 알리지 않고 [ReadOutcome.NOT_FOUND]다. */
    fun markRead(
        id: Long,
        receiverId: Long,
        now: Instant,
    ): ReadOutcome {
        val marked =
            jdbcClient
                .sql(
                    """
                    update notification n set read_at = :now
                    where n.id = :id and n.receiver_id = :receiverId and n.read_at is null
                      and ${NotificationRetention.condition("n")}
                    """.trimIndent(),
                ).param("now", Timestamp.from(now))
                .param("id", id)
                .param("receiverId", receiverId)
                .params(retention.params())
                .update()
        if (marked == 1) return ReadOutcome.MARKED
        val exists =
            jdbcClient
                .sql(
                    """
                    select exists (
                        select 1 from notification n
                        where n.id = :id and n.receiver_id = :receiverId and ${NotificationRetention.condition("n")}
                    )
                    """.trimIndent(),
                ).param("id", id)
                .param("receiverId", receiverId)
                .params(retention.params())
                .query(Boolean::class.java)
                .single()
        return if (exists) ReadOutcome.ALREADY_READ else ReadOutcome.NOT_FOUND
    }

    /** 모두 읽음(research R11). [upToSeq] 이하만 바꾸고, 바꾼 행 수를 돌려준다. */
    fun markAllRead(
        receiverId: Long,
        upToSeq: Long,
        now: Instant,
    ): Int =
        jdbcClient
            .sql(
                """
                update notification n set read_at = :now
                where n.receiver_id = :receiverId and n.read_at is null and n.seq <= :upToSeq
                  and ${NotificationRetention.condition("n")}
                """.trimIndent(),
            ).param("now", Timestamp.from(now))
            .param("receiverId", receiverId)
            .param("upToSeq", upToSeq)
            .params(retention.params())
            .update()

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Notification(
                    id = rs.getLong("id"),
                    receiverId = rs.getLong("receiver_id"),
                    type = NotificationType.valueOf(rs.getString("type")),
                    postId = rs.getLong("post_id"),
                    commentId = rs.nullableLong("comment_id"),
                    monsterId = rs.nullableLong("monster_id"),
                    latestActorId = rs.nullableLong("latest_actor_id"),
                    actorCount = rs.getInt("actor_count"),
                    dedupKey = rs.getString("dedup_key"),
                    seq = rs.getLong("seq"),
                    readAt = rs.getTimestamp("read_at")?.toInstant(),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }

        fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }
    }
}
