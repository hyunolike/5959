package com.ogu.notification.application

import com.ogu.member.MemberSessionsEnded
import com.ogu.notification.stream.LocalSignalPublisher
import com.ogu.notification.stream.NotificationSignal
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * 비밀번호를 재설정해 그 회원의 로그인 상태가 모두 끝나면 열려 있는 실시간 연결도 닫는다(015 FR-006). 받은 알림은 그대로
 * 둔다. 새 비밀번호로 로그인하면 다시 본다.
 */
@Component
class SessionsEndedListener(
    private val signals: LocalSignalPublisher,
) {
    @ApplicationModuleListener
    fun on(event: MemberSessionsEnded) {
        signals.publish(NotificationSignal.Closed(event.memberId))
    }
}
