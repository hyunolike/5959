package com.ogu.notification.stream

/**
 * 알림 신호(018 research R4). 알림 내용은 싣지 않는다. 받는 쪽은 번호로 DB에서 다시 읽으므로 신호를 잃거나 두 번 받아도
 * 알림이 빠지거나 겹치지 않는다.
 *
 * - [New]: 그 회원에게 [New.seq]번 알림이 커밋됐다. 번호는 따라잡기를 건너뛸지 판단하는 힌트다.
 * - [Read]: 그 회원의 읽음 상태가 바뀌었다(안 읽은 수를 다시 보낸다).
 * - [Closed]: 그 회원이 탈퇴했거나 비밀번호를 바꿨다(013 research R7, 015 FR-006). 그 회원의 연결을 닫는다.
 */
sealed interface NotificationSignal {
    val memberId: Long

    data class New(
        override val memberId: Long,
        val seq: Long,
    ) : NotificationSignal

    data class Read(
        override val memberId: Long,
    ) : NotificationSignal

    data class Closed(
        override val memberId: Long,
    ) : NotificationSignal
}
