package com.ogu.member.application

import com.ogu.member.domain.AuthSession
import com.ogu.member.domain.AuthSessionRepository
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.member.infrastructure.security.JwtIssuer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 세션 발급과 유효성 확인. refresh(T061)와 무효화(T047)는 해당 스토리에서 추가한다.
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
     * 요청마다 부른다. PK 조회 한 번으로 `revoked_at IS NULL AND now < expires_at`을 확인한다.
     */
    @Transactional(readOnly = true)
    fun isActive(sessionId: UUID): Boolean =
        repository
            .findById(sessionId)
            .map { it.isActive(clock.instant()) }
            .orElse(false)
}
