package com.ogu.notification.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/**
 * 공감 묶음 참여자 `like_notification_participant`(research R7). 기본 키 `(post_id, liker_id)`가 "같은 회원의 같은 글
 * 공감은 한 번만 알린다"를 보장한다. 쓰기 순서는 묶음 갱신 또는 생성이 먼저고, 그 ID로 참여자를 넣는다.
 */
@Repository
class LikeParticipantRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 참여자를 넣는다. 이미 있으면(이미 알린 공감) 아무것도 하지 않고 false다. 그때 호출한 쪽은 트랜잭션 전체를 되돌려
     * 번호와 묶음 갱신을 취소한다.
     */
    fun insertIfAbsent(
        postId: Long,
        likerId: Long,
        notificationId: Long,
        createdAt: Instant,
    ): Boolean =
        jdbcClient
            .sql(
                """
                insert into like_notification_participant (post_id, liker_id, notification_id, created_at)
                values (:postId, :likerId, :notificationId, :createdAt)
                on conflict on constraint like_notification_participant_pkey do nothing
                """.trimIndent(),
            ).param("postId", postId)
            .param("likerId", likerId)
            .param("notificationId", notificationId)
            .param("createdAt", Timestamp.from(createdAt))
            .update() == 1
}
