package com.ogu.notification.application

import com.ogu.notification.domain.NotificationType
import java.time.LocalDate

/**
 * 쓸 알림 하나(번호를 받기 전). 멱등 키 형식은 research R6, data-model.md "종류" 표 그대로다. 공감 묶음은 키가 없어
 * [NotificationWriter.addLike]로 따로 쓴다.
 */
data class NotificationDraft(
    val receiverId: Long,
    val type: NotificationType,
    /** 보스 처치 알림(006)과 주간 리포트 알림(008)은 글이 없다. */
    val postId: Long?,
    val commentId: Long? = null,
    val monsterId: Long? = null,
    val actorId: Long? = null,
    val dedupKey: String,
    val raidBossId: Long? = null,
    val reportWeekStart: LocalDate? = null,
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

        /**
         * 작성자에게 가는 안전 알림(005 research R7, R8): 도움 안내, 숨김 해제, 재검토 결과. 받는 사람은 그 글이나 댓글의
         * 작성자다. [commentId]는 대상이 댓글일 때만 있다. 멱등 키는 부르는 쪽이 종류에 맞게 만든다.
         */
        fun toAuthor(
            type: NotificationType,
            receiverId: Long,
            postId: Long,
            commentId: Long?,
            dedupKey: String,
        ): NotificationDraft {
            require(type in AUTHOR_TYPES) { "작성자에게 가는 안전 알림 종류가 아닙니다: $type" }
            return NotificationDraft(receiverId, type, postId, commentId = commentId, dedupKey = dedupKey)
        }

        /** 보스를 함께 물리쳤다(006 research R11). 보스마다 회원에게 한 번이다. 글이 없다. */
        fun raidDefeated(
            receiverId: Long,
            bossId: Long,
        ): NotificationDraft =
            NotificationDraft(
                receiverId,
                NotificationType.RAID_BOSS_DEFEATED,
                postId = null,
                dedupKey = "RAID:$bossId",
                raidBossId = bossId,
            )

        /** 주간 리포트가 발행됐다(008 research R4). 주마다 회원에게 한 번이다. 글이 없다. */
        fun weeklyReport(
            receiverId: Long,
            weekStart: LocalDate,
        ): NotificationDraft =
            NotificationDraft(
                receiverId,
                NotificationType.WEEKLY_REPORT,
                postId = null,
                dedupKey = "WEEKLY_REPORT:$weekStart",
                reportWeekStart = weekStart,
            )

        private val COMMENT_TYPES =
            setOf(NotificationType.POST_COMMENT, NotificationType.POST_REPLY, NotificationType.COMMENT_REPLY)
        private val AUTHOR_TYPES =
            setOf(NotificationType.SUPPORT_NOTICE, NotificationType.CONTENT_RESTORED, NotificationType.REVIEW_KEPT)
        private val DEFEATED_TYPES =
            setOf(NotificationType.MONSTER_DEFEATED, NotificationType.MONSTER_DEFEATED_TOGETHER)
    }
}
