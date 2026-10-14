package com.ogu.member.application

import com.ogu.member.MemberSessionsEnded
import com.ogu.member.domain.AuthSessionRepository
import com.ogu.member.domain.OAuthIdentityRepository
import com.ogu.member.domain.PasswordResetTokenRepository
import com.ogu.member.domain.SseTicketRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * 회원이 로그인하는 데 쓰는 것들(세션, 실시간 알림 연결 표, 외부 계정 연결, 비밀번호 재설정 링크)을 한꺼번에 끝낸다.
 * 탈퇴(013)와 비밀번호 재설정(015)이 함께 쓴다. 부르는 쪽의 트랜잭션 안에서 돈다.
 */
@Component
class MemberCredentials(
    private val sessions: AuthSessionRepository,
    private val tickets: SseTicketRepository,
    private val identities: OAuthIdentityRepository,
    private val resetTokens: PasswordResetTokenRepository,
    private val events: ApplicationEventPublisher,
) {
    /**
     * 모든 로그인 상태를 끝낸다. 세션 행이 없으면 이미 받은 access 토큰도 `SessionCheckFilter`가 401로 막고, 쓰지 않은 연결
     * 표로도 스트림에 붙지 못한다. 이미 열린 스트림은 닫지 않는다(탈퇴는 `MemberWithdrawn`으로 닫는다).
     */
    fun endSessions(memberId: Long) {
        sessions.deleteAllByMemberId(memberId)
        tickets.deleteAllByMember(memberId)
    }

    /**
     * [endSessions]에 더해 이미 열린 알림 스트림도 커밋 뒤에 닫는다([MemberSessionsEnded]). 비밀번호를 바꿨을 때 쓴다.
     * 비밀번호를 안 다른 사람이 열어 둔 스트림이 그 회원의 알림을 계속 받지 않게 한다.
     */
    fun signOutEverywhere(memberId: Long) {
        endSessions(memberId)
        events.publishEvent(MemberSessionsEnded(memberId))
    }

    /** 로그인 수단까지 모두 지운다. 탈퇴할 때 쓴다(013 research R1, 015 FR-018). 같은 외부 계정으로 오면 새 회원이 된다. */
    fun deleteAll(memberId: Long) {
        identities.deleteAllByMemberId(memberId)
        endSessions(memberId)
        resetTokens.deleteByMember(memberId)
    }
}
