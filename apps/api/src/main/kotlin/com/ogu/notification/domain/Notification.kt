package com.ogu.notification.domain

import java.time.Instant

/**
 * 저장된 알림 한 행(data-model.md `notification`). [readAt]이 null이면 안 읽음이다. 공감 묶음(`POST_LIKE`)만
 * [actorCount]가 1보다 클 수 있고 [dedupKey]가 null이다.
 */
data class Notification(
    val id: Long,
    val receiverId: Long,
    val type: NotificationType,
    /** 보스 처치 알림(006)은 글이 없어 null이다. */
    val postId: Long?,
    val commentId: Long?,
    val monsterId: Long?,
    val latestActorId: Long?,
    val actorCount: Int,
    val dedupKey: String?,
    val seq: Long,
    val readAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 멱등 키로 한 번만 넣는 알림(댓글, 답글, 몬스터 알림, research R6). 공감 묶음은 [NotificationRepository.upsertLikeGroup]을 쓴다. */
data class NewNotification(
    val receiverId: Long,
    val type: NotificationType,
    val postId: Long?,
    val commentId: Long?,
    val monsterId: Long?,
    val actorId: Long?,
    val dedupKey: String,
    val seq: Long,
    val createdAt: Instant,
    val raidBossId: Long? = null,
) {
    init {
        require(type != NotificationType.POST_LIKE) { "공감 알림은 묶음으로만 만든다" }
    }
}

/** 하나 읽음의 결과(research R11). [NOT_FOUND]는 없거나, 남의 알림이거나, 보관 기간이 지난 경우다. */
enum class ReadOutcome {
    MARKED,
    ALREADY_READ,
    NOT_FOUND,
}
