package com.ogu.notification.application

import com.ogu.member.MemberSessionsEnded
import com.ogu.notification.stream.NotificationSignal
import com.ogu.notification.stream.RedisSignalPublisher
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * 비밀번호를 재설정해 그 회원의 로그인 상태가 모두 끝나면 열려 있는 실시간 연결도 닫는다(015 FR-006). 받은 알림은 그대로
 * 둔다. 새 비밀번호로 로그인하면 다시 본다. Redis가 내려가 있으면 연결은 수명(15분)이 지나며 닫힌다.
 */
@Component
class SessionsEndedListener(
    private val signals: RedisSignalPublisher,
) {
    @ApplicationModuleListener
    fun on(event: MemberSessionsEnded) {
        signals.publish(NotificationSignal.Closed(event.memberId))
    }
}
