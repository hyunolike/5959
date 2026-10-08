package com.ogu.member.application

import com.ogu.member.StreamTicket
import com.ogu.member.domain.SseTicketRepository
import com.ogu.member.infrastructure.config.StreamTicketProperties
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * SSE 연결 표(004 research R3). 브라우저가 access 토큰을 주소에 싣지 않고 API 도메인의 스트림에 붙게 하는 일회용 표다.
 *
 * - 원문은 32바이트 난수의 base64url이고, 저장은 SHA-256 해시만 한다. 주소가 로그에 남아도 다시 쓸 수 없다.
 * - [StreamTicketProperties.ttl](30초) 안에 한 번만 쓸 수 있다. 소비는 조건부 UPDATE 한 문장이라 동시에 두 번 붙어도
 *   한 번만 성공한다. 인스턴스가 둘이어도 DB가 판정한다.
 * - 소비한 뒤 발급한 세션이 아직 유효한지 본다. 로그아웃이나 세션 무효화 뒤에는 남은 표로도 붙지 못한다.
 */
@Service
class StreamTicketService(
    private val tickets: SseTicketRepository,
    private val sessions: SessionService,
    private val properties: StreamTicketProperties,
    private val clock: Clock,
) {
    fun issue(
        memberId: Long,
        sessionId: UUID,
    ): StreamTicket {
        val ticket = RefreshTokens.generate()
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val expiresAt = now.plus(properties.ttl)
        tickets.insert(RefreshTokens.hash(ticket), memberId, sessionId, now, expiresAt)
        return StreamTicket(ticket, expiresAt)
    }

    /** 성공하면 회원 ID. 없는 표, 이미 쓴 표, 만료된 표, 세션이 끝난 표면 null이다. */
    fun consume(ticket: String): Long? {
        val wellFormed = ticket.isNotEmpty() && ticket.length <= MAX_TICKET_LENGTH
        val consumed = if (wellFormed) tickets.consume(RefreshTokens.hash(ticket), clock.instant()) else null
        return consumed?.memberId?.takeIf { sessions.isActive(consumed.sessionId, it) }
    }

    private companion object {
        /** 계약의 `maxLength`. 원문은 43자다. */
        const val MAX_TICKET_LENGTH = 64
    }
}
