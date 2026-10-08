package com.ogu.notification.application

import com.ogu.notification.domain.NotificationRepository
import com.ogu.notification.domain.NotificationSequenceRepository
import com.ogu.notification.domain.ReadOutcome
import com.ogu.notification.stream.NotificationSignal
import com.ogu.notification.stream.RedisSignalPublisher
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 읽음 처리(FR-008, research R11). 읽음이 실제로 바뀌었을 때만 커밋 뒤 `{memberId}:r` 신호를 예약한다
 * ([RedisSignalPublisher]). 신호를 받은 인스턴스마다 그 회원의 모든 연결에 `unread-count` 이벤트를 보내 다른 탭의 배지도
 * 바뀐다. 이 이벤트는 재전송하지 않는다. 신호를 놓친 연결은 다시 붙을 때 안 읽은 수를 새로 받는다.
 *
 * 공감 묶음과 겹칠 때(research R7): 읽음의 `UPDATE`와 묶음 갱신은 같은 행 잠금을 두고 줄을 선다. 읽음이 먼저 커밋되면
 * 안 읽은 묶음 자리(부분 유일 인덱스)가 비어 다음 공감이 새 묶음을 만든다. 공감이 먼저면 번호가 올라가, 기다리던 모두
 * 읽음이 `seq <= upToSeq`를 다시 확인하고 그 묶음을 건너뛴다.
 *
 * 하나 읽음은 다르다. 번호가 아니라 알림 ID로 읽으므로, 기다리는 사이에 공감이 더해진 묶음은 더해진 공감까지 함께 읽음이
 * 된다. 의도한 동작이다. 회원이 바로 그 묶음을 눌렀고, 눌러서 가는 글 상세에서 새 공감도 보게 된다. 새 공감은 묶음의
 * 인원 수에는 들어가지만 따로 안 읽음으로 남지 않고, 그다음 공감부터 새 묶음이 된다.
 */
@Service
class NotificationReadService(
    private val notifications: NotificationRepository,
    private val sequences: NotificationSequenceRepository,
    private val signals: RedisSignalPublisher,
    private val clock: Clock,
) {
    /**
     * 하나 읽음. 이미 읽었으면 아무것도 바꾸지 않는다. 없거나, 남의 알림이거나, 보관 기간이 지났으면 존재 여부를 알리지
     * 않고 404 NOTIFICATION_NOT_FOUND다(US2-AC6).
     */
    @Transactional
    fun markOne(
        memberId: Long,
        notificationId: Long,
    ) {
        when (notifications.markRead(notificationId, memberId, now())) {
            ReadOutcome.MARKED -> signals.publish(NotificationSignal.Read(memberId))
            ReadOutcome.ALREADY_READ -> Unit
            ReadOutcome.NOT_FOUND -> throw BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND)
        }
    }

    /**
     * 모두 읽음. [upToSeq](웹이 지금까지 받은 가장 큰 번호) 이하만 바꾼다. 누르는 사이에 온 알림은 번호가 더 커서 안 읽은
     * 채로 남는다(US2-AC4). [upToSeq]가 없거나 음수면 400 INVALID_REQUEST.
     *
     * [upToSeq]는 이 회원의 지금 마지막 번호를 넘지 않게 낮춘다(스트림이 `lastEventId`를 낮추는 것과 같다). 한참 큰 값을
     * 보내도 이 요청이 번호를 읽은 뒤에 커밋되는 알림과 묶음 갱신은 읽음이 되지 않는다.
     */
    @Transactional
    fun markAll(
        memberId: Long,
        upToSeq: Long?,
    ): ReadAllResult {
        if (upToSeq == null || upToSeq < 0) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "upToSeq는 0 이상이어야 합니다.")
        }
        val boundedSeq = minOf(upToSeq, sequences.current(memberId))
        val updated = notifications.markAllRead(memberId, boundedSeq, now())
        if (updated > 0) signals.publish(NotificationSignal.Read(memberId))
        return ReadAllResult(updated = updated, unreadCount = notifications.countUnread(memberId))
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}

/** 모두 읽음의 결과. [updated]는 이번에 읽음이 된 수, [unreadCount]는 처리 뒤 남은 안 읽은 수다. */
data class ReadAllResult(
    val updated: Int,
    val unreadCount: Long,
)
