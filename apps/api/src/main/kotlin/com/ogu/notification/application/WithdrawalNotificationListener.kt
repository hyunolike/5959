package com.ogu.notification.application

import com.ogu.member.MemberWithdrawn
import com.ogu.notification.domain.WithdrawnReceiverRepository
import com.ogu.notification.stream.NotificationSignal
import com.ogu.notification.stream.RedisSignalPublisher
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * 회원이 탈퇴하면 그 회원이 받은 알림과 번호 행을 지우고, 열려 있는 실시간 연결을 닫는다(013 FR-008, research R7).
 * 커밋 뒤 새 트랜잭션에서 돌고, 실패하면 발행 기록에 남아 다시 온다. 다시 와도 지울 것이 없을 뿐이다.
 *
 * 다른 회원이 받은 알림은 건드리지 않는다. 탈퇴한 회원이 행동한 회원으로 남은 알림은 닉네임이 없어 화면에 "누군가"로 보인다.
 * 연결을 닫는 신호는 커밋 뒤에 나간다. Redis가 내려가 있으면 연결은 수명(15분)이 지나며 닫히고, 그 사이에 갈 알림은 없다
 * ([NotificationWriter]가 탈퇴한 회원에게 쓰지 않는다).
 */
@Component
class WithdrawalNotificationListener(
    private val received: WithdrawnReceiverRepository,
    private val signals: RedisSignalPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun on(event: MemberWithdrawn) {
        val deleted = received.deleteAllOf(event.memberId)
        signals.publish(NotificationSignal.Closed(event.memberId))
        log.info("탈퇴한 회원이 받은 알림을 지웠습니다: memberId={}, notifications={}", event.memberId, deleted)
    }
}
