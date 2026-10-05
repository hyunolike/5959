package com.ogu.notification.application

import com.ogu.notification.domain.NotificationType

/**
 * 쓸 알림 하나(번호를 받기 전). 멱등 키 형식은 research R6, data-model.md "종류" 표 그대로다. 공감 묶음은 키가 없어
 * [NotificationWriter.addLike]로 따로 쓴다.
 */
data class NotificationDraft(
    val receiverId: Long,
    val type: NotificationType,
    val postId: Long,
    val commentId: Long? = null,
    val monsterId: Long? = null,
    val actorId: Long? = null,
    val dedupKey: String,
) {
    companion object {
        /** 댓글과 답글 알림. 글쓴이와 원 댓글 주인이 같은 키를 써서 한 답글로 한 회원에게 두 개가 생기지 않는다. */
        fun comment(
            type: NotificationType,
            receiverId: Long,
            postId: Long,
            commentId: Long,
            actorId: Long,
        ): NotificationDraft {
            require(type in COMMENT_TYPES) { "댓글 알림 종류가 아닙니다: $type" }
            return NotificationDraft(
                receiverId,
                type,
                postId,
                commentId = commentId,
                actorId = actorId,
                dedupKey = "COMMENT:$commentId",
            )
        }

        fun spawned(
            receiverId: Long,
            postId: Long,
            monsterId: Long,
        ): NotificationDraft =
            NotificationDraft(
                receiverId,
                NotificationType.MONSTER_SPAWNED,
                postId,
                monsterId = monsterId,
                dedupKey = "SPAWNED:$monsterId",
            )

        /** 글쓴이는 [NotificationType.MONSTER_DEFEATED], 함께 공격한 회원은 [NotificationType.MONSTER_DEFEATED_TOGETHER]. */
        fun defeated(
            type: NotificationType,
            receiverId: Long,
            postId: Long,
            monsterId: Long,
        ): NotificationDraft {
            require(type in DEFEATED_TYPES) { "처치 알림 종류가 아닙니다: $type" }
            return NotificationDraft(receiverId, type, postId, monsterId = monsterId, dedupKey = "DEFEATED:$monsterId")
        }

        private val COMMENT_TYPES =
            setOf(NotificationType.POST_COMMENT, NotificationType.POST_REPLY, NotificationType.COMMENT_REPLY)
        private val DEFEATED_TYPES =
            setOf(NotificationType.MONSTER_DEFEATED, NotificationType.MONSTER_DEFEATED_TOGETHER)
    }
}
