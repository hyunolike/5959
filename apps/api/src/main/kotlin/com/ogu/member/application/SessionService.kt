package com.ogu.member.application

import com.ogu.member.domain.AuthSession
import com.ogu.member.domain.AuthSessionRepository
import com.ogu.member.domain.SessionRevokeReason
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.member.infrastructure.security.JwtIssuer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 세션 발급, 유효성 확인, 무효화. refresh(T061)는 US4에서 추가한다.
 */
@Service
class SessionService(
    private val repository: AuthSessionRepository,
    private val jwtIssuer: JwtIssuer,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    /**
     * 새 세션을 만들고 access, refresh 토큰을 발급한다. 가입, 로그인과 같은 트랜잭션에서 부른다.
     */
    @Transactional
    fun issue(
        memberId: Long,
        onboarded: Boolean,
    ): IssuedTokens {
        val now = clock.instant()
        val refreshToken = RefreshTokens.generate()
        val session =
            repository.save(
                AuthSession.start(
                    memberId = memberId,
                    refreshTokenHash = RefreshTokens.hash(refreshToken),
                    now = now,
                    idleTtl = properties.session.idleTtl,
                    absoluteTtl = properties.session.absoluteTtl,
                ),
            )
        val accessToken = jwtIssuer.issue(memberId = memberId, sessionId = session.id, onboarded = onboarded)
        return IssuedTokens(
            sessionId = session.id,
            accessToken = accessToken.value,
            accessTokenExpiresAt = accessToken.expiresAt,
            refreshToken = refreshToken,
            refreshTokenExpiresAt = session.expiresAt,
        )
    }

    /**
     * 요청마다 부른다. PK 조회 한 번으로 `revoked_at IS NULL AND now < expires_at`과
     * 세션의 회원이 토큰의 `sub`와 같은지 확인한다.
     */
    @Transactional(readOnly = true)
    fun isActive(
        sessionId: UUID,
        memberId: Long,
    ): Boolean =
        repository
            .findById(sessionId)
            .map { it.memberId == memberId && it.isActive(clock.instant()) }
            .orElse(false)

    /**
     * 세션을 무효로 한다(FR-011). 이미 무효인 세션은 처음 무효가 된 시각과 이유를 그대로 둔다.
     */
    @Transactional
    fun revoke(
        sessionId: UUID,
        reason: SessionRevokeReason,
    ) {
        repository.findById(sessionId).ifPresent { it.revoke(reason, clock.instant()) }
    }
}
