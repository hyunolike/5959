package com.ogu.notification.application

import com.ogu.notification.domain.NotificationRepository
import com.ogu.notification.domain.NotificationSequenceRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** 알림 읽기(research R11, R12). 보관 기간 조건은 저장소가 붙인다. */
@Service
class NotificationQueryService(
    private val notifications: NotificationRepository,
    private val sequences: NotificationSequenceRepository,
    private val views: NotificationViewAssembler,
) {
    /**
     * 안 읽은 수와 마지막 전달 번호. 웹은 [UnreadCount.latestSeq]를 첫 연결의 `lastEventId`로 넘긴다(research R4).
     * 두 값을 한 스냅숏에서 읽어, 그 사이에 커밋된 알림이 수에는 들어가고 번호에는 빠지는 일이 없게 한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun unreadCount(memberId: Long): UnreadCount =
        UnreadCount(count = notifications.countUnread(memberId), latestSeq = sequences.current(memberId))

    /**
     * 목록 한 쪽(US2-AC1, AC2). 번호 내림차순 키셋이라 묶인 공감은 마지막 공감 기준으로 위에 온다. 쪽 사이에 묶음이 갱신되면
     * 번호가 커져 위로 올라갈 뿐 다음 쪽에 다시 나오지 않는다. 쿼리는 쪽 크기와 상관없이 알림, 글 미리보기, 행동한 회원
     * 세 개다. 화면에 붙이는 것은 실시간 스트림과 같은 [NotificationViewAssembler]가 만든다.
     *
     * [size]가 1~[MAX_PAGE_SIZE] 밖이거나 [cursor]가 올바르지 않으면 400 INVALID_REQUEST.
     */
    fun page(
        memberId: Long,
        cursor: String?,
        size: Int,
    ): NotificationPage {
        if (size !in 1..MAX_PAGE_SIZE) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "size는 1 이상 $MAX_PAGE_SIZE 이하여야 합니다.")
        }
        val beforeSeq = cursor?.let(NotificationCursor::decode)
        // 하나 더 읽어 다음 쪽이 있는지 본다
        val rows = notifications.findPage(memberId, beforeSeq, size + 1)
        val items = rows.take(size)
        val nextCursor = if (rows.size > size) NotificationCursor.encode(items.last().seq) else null
        return NotificationPage(items = views.assemble(items), nextCursor = nextCursor)
    }

    companion object {
        const val MAX_PAGE_SIZE = 50
    }
}

/** 안 읽은 알림 수 [count]와 이 회원의 마지막 전달 번호 [latestSeq](알림이 없으면 0). */
data class UnreadCount(
    val count: Long,
    val latestSeq: Long,
)

/** 알림 목록 한 쪽. 다음 쪽이 없으면 [nextCursor]는 null이다. */
data class NotificationPage(
    val items: List<NotificationView>,
    val nextCursor: String?,
)
