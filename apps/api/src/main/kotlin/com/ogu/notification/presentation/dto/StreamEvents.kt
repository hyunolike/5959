package com.ogu.notification.presentation.dto

/** SSE `event: notification`의 data(계약 `StreamNotificationEvent`). SSE `id`는 [NotificationResponse.seq]다. */
data class StreamNotificationEvent(
    val notification: NotificationResponse,
    val unreadCount: Long,
)

/** SSE `event: unread-count`의 data(계약 `StreamUnreadCountEvent`). id가 없고 재전송하지 않는다. */
data class StreamUnreadCountEvent(
    val unreadCount: Long,
)
