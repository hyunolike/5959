package com.ogu.member.application

import com.ogu.member.domain.AuthSession
import com.ogu.member.domain.AuthSessionRepository
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.SessionRevokeReason
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.member.infrastructure.security.JwtIssuer
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 세션 발급, 갱신, 유효성 확인, 무효화.
 */
@Service
class SessionService(
    private val repository: AuthSessionRepository,
    private val memberRepository: MemberRepository,
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
     * refresh 토큰으로 세션을 갱신한다(US4-AC1~AC3, data-model.md refresh 흐름도, research R2).
     *
     * - 현재 토큰: 세션이 유효하면 토큰을 교체하고 access와 새 refresh를 준다. 아니면 `SESSION_EXPIRED`.
     * - 직전 토큰, 교체 후 유예(`ogu.auth.session.rotation-grace`) 안: 세션이 유효하면 access만 준다(refresh는 null).
     *   여러 탭이 같은 토큰으로 동시에 갱신한 경우다.
     * - 직전 토큰, 유예 뒤: 탈취로 보고 세션을 `REUSE_DETECTED`로 무효화한 뒤 `SESSION_EXPIRED`.
     * - 그 밖의 토큰: `SESSION_EXPIRED`.
     *
     * 세션 행을 `SELECT ... FOR UPDATE`로 잠가 같은 세션의 갱신을 직렬화한다. 재사용 감지로 무효화한 기록은 `401`을
     * 던진 뒤에도 남아야 하므로 [BusinessException]으로는 롤백하지 않는다(이 메서드는 그 전에 다른 쓰기를 하지 않는다).
     */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun refresh(refreshToken: String): RefreshResult {
        val now = clock.instant()
        val hash = RefreshTokens.hash(refreshToken)
        val session =
            refreshToken
                .takeIf { it.isNotEmpty() }
                ?.let { repository.findByRefreshTokenHashForUpdate(hash) }
        val decision =
            when {
                session == null -> RefreshDecision.Expired
                hash == session.refreshTokenHash -> rotateIfActive(session, now)
                !session.isWithinRotationGrace(hash, now, properties.session.rotationGrace) -> {
                    session.revoke(SessionRevokeReason.REUSE_DETECTED, now)
                    RefreshDecision.Expired
                }
                session.isActive(now) -> RefreshDecision.AccessOnly
                else -> RefreshDecision.Expired
            }
        return when (decision) {
            RefreshDecision.Expired -> throw sessionExpired()
            RefreshDecision.AccessOnly -> result(requireNotNull(session), refreshToken = null)
            is RefreshDecision.Rotated -> result(requireNotNull(session), decision.refreshToken)
        }
    }

    private fun rotateIfActive(
        session: AuthSession,
        now: Instant,
    ): RefreshDecision {
        if (!session.isActive(now)) return RefreshDecision.Expired
        val newRefreshToken = RefreshTokens.generate()
        session.rotate(RefreshTokens.hash(newRefreshToken), now, properties.session.idleTtl)
        return RefreshDecision.Rotated(newRefreshToken)
    }

    private fun result(
        session: AuthSession,
        refreshToken: String?,
    ): RefreshResult {
        val member = memberRepository.findById(session.memberId).orElseThrow { sessionExpired() }
        val accessToken =
            jwtIssuer.issue(memberId = member.id, sessionId = session.id, onboarded = member.isOnboarded)
        return RefreshResult(
            member = member,
            tokens =
                IssuedTokens(
                    sessionId = session.id,
                    accessToken = accessToken.value,
                    accessTokenExpiresAt = accessToken.expiresAt,
                    refreshToken = refreshToken,
                    refreshTokenExpiresAt = session.expiresAt,
                ),
        )
    }

    private fun sessionExpired() = BusinessException(ErrorCode.SESSION_EXPIRED)

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

data class RefreshResult(
    val member: Member,
    val tokens: IssuedTokens,
)

private sealed interface RefreshDecision {
    data object Expired : RefreshDecision

    data object AccessOnly : RefreshDecision

    data class Rotated(
        val refreshToken: String,
    ) : RefreshDecision
}
