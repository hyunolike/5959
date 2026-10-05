package com.ogu.notification.application

import com.ogu.notification.domain.NotificationRepository
import com.ogu.notification.domain.NotificationSequenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** 알림 읽기(research R11). 보관 기간 조건은 저장소가 붙인다. */
@Service
class NotificationQueryService(
    private val notifications: NotificationRepository,
    private val sequences: NotificationSequenceRepository,
) {
    /**
     * 안 읽은 수와 마지막 전달 번호. 웹은 [UnreadCount.latestSeq]를 첫 연결의 `lastEventId`로 넘긴다(research R4).
     * 두 값을 한 스냅숏에서 읽어, 그 사이에 커밋된 알림이 수에는 들어가고 번호에는 빠지는 일이 없게 한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun unreadCount(memberId: Long): UnreadCount =
        UnreadCount(count = notifications.countUnread(memberId), latestSeq = sequences.current(memberId))
}

/** 안 읽은 알림 수 [count]와 이 회원의 마지막 전달 번호 [latestSeq](알림이 없으면 0). */
data class UnreadCount(
    val count: Long,
    val latestSeq: Long,
)
