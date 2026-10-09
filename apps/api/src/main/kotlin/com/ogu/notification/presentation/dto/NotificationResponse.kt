package com.ogu.notification.presentation.dto

import com.ogu.notification.application.NotificationView
import com.ogu.notification.domain.NotificationType
import java.time.Instant

/** 계약의 `Notification`. 목록 API와 실시간 스트림이 같은 모양을 쓴다. */
data class NotificationResponse(
    val notificationId: Long,
    val seq: Long,
    val type: NotificationType,
    val postId: Long?,
    val post: NotificationPostResponse?,
    val commentId: Long?,
    val actor: NotificationActorResponse?,
    val actorCount: Int,
    val read: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(view: NotificationView): NotificationResponse {
            val n = view.notification
            return NotificationResponse(
                notificationId = n.id,
                seq = n.seq,
                type = n.type,
                postId = n.postId,
                post = view.post?.let { NotificationPostResponse(it.postId, it.contentPreview) },
                commentId = n.commentId,
                actor = view.actor?.let { NotificationActorResponse(it.id, it.nickname) },
                actorCount = n.actorCount,
                read = n.readAt != null,
                createdAt = n.createdAt,
                updatedAt = n.updatedAt,
            )
        }
    }
}

/** 계약의 `NotificationPost`. */
data class NotificationPostResponse(
    val postId: Long,
    val contentPreview: String,
)

/** 계약의 `NotificationActor`. 닉네임은 지금 닉네임이다. */
data class NotificationActorResponse(
    val id: Long,
    val nickname: String,
)
