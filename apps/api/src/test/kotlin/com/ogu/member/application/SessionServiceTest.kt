package com.ogu.member.application

import com.ogu.member.domain.AuthSession
import com.ogu.member.domain.AuthSessionRepository
import com.ogu.member.domain.SessionRevokeReason
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.member.infrastructure.security.JwtIssuer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.Optional
import java.util.UUID

class SessionServiceTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val properties =
        AuthProperties(
            jwt = AuthProperties.Jwt(secret = SECRET, accessTokenTtl = Duration.ofMinutes(15)),
            session =
                AuthProperties.Session(
                    idleTtl = Duration.ofDays(14),
                    absoluteTtl = Duration.ofDays(30),
                    rotationGrace = Duration.ofSeconds(30),
                ),
        )
    private val repository: AuthSessionRepository = mock(AuthSessionRepository::class.java)
    private val service = SessionService(repository, JwtIssuer(properties, clock), properties, clock)

    init {
        given(repository.save(any(AuthSession::class.java))).willAnswer { it.arguments[0] }
    }

    @Test
    fun `세션을 발급하면 refresh 토큰은 32바이트 base64url이고 저장된 해시는 그 SHA-256 hex다`() {
        val tokens = service.issue(memberId = 42L, onboarded = false)

        val saved = savedSession()
        val refreshToken = requireNotNull(tokens.refreshToken)
        assertThat(refreshToken).matches("^[A-Za-z0-9_-]{43}$")
        assertThat(Base64.getUrlDecoder().decode(refreshToken)).hasSize(32)
        assertThat(saved.refreshTokenHash).isEqualTo(sha256Hex(refreshToken))
        assertThat(saved.refreshTokenHash).hasSize(64).matches("^[0-9a-f]{64}$")
    }

    @Test
    fun `세션 만료는 발급 시각에서 14일, 최대 만료는 30일 뒤다`() {
        val tokens = service.issue(memberId = 42L, onboarded = false)

        val saved = savedSession()
        assertThat(saved.memberId).isEqualTo(42L)
        assertThat(saved.createdAt).isEqualTo(now)
        assertThat(saved.expiresAt).isEqualTo(now.plus(Duration.ofDays(14)))
        assertThat(saved.absoluteExpiresAt).isEqualTo(now.plus(Duration.ofDays(30)))
        assertThat(saved.revokedAt).isNull()
        assertThat(saved.previousRefreshTokenHash).isNull()
        assertThat(saved.rotatedAt).isNull()
        assertThat(tokens.sessionId).isEqualTo(saved.id)
        assertThat(tokens.refreshTokenExpiresAt).isEqualTo(saved.expiresAt)
    }

    @Test
    fun `access 토큰은 세션 ID와 회원 ID, 온보딩 여부를 담고 15분 뒤 만료된다`() {
        val tokens = service.issue(memberId = 42L, onboarded = true)

        val jwt = JwtIssuer.decoder(SECRET, clock).decode(tokens.accessToken)
        assertThat(jwt.subject).isEqualTo("42")
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(tokens.sessionId.toString())
        assertThat(jwt.getClaim<Boolean>("onboarded")).isTrue()
        assertThat(tokens.accessTokenExpiresAt).isEqualTo(now.plus(Duration.ofMinutes(15)))
    }

    @Test
    fun `발급할 때마다 세션 ID와 refresh 토큰이 다르다`() {
        val first = service.issue(memberId = 42L, onboarded = false)
        val second = service.issue(memberId = 42L, onboarded = false)

        assertThat(first.sessionId).isNotEqualTo(second.sessionId)
        assertThat(first.refreshToken).isNotEqualTo(second.refreshToken)
    }

    @Test
    fun `무효화되지 않았고 만료 전인 세션은 유효하다`() {
        val session = storedSession(expiresAt = now.plusSeconds(1))

        assertThat(service.isActive(session.id)).isTrue()
    }

    @Test
    fun `만료 시각이 지난 세션은 유효하지 않다`() {
        val session = storedSession(expiresAt = now)

        assertThat(service.isActive(session.id)).isFalse()
    }

    @Test
    fun `무효화된 세션은 만료 전이어도 유효하지 않다`() {
        val session = storedSession(expiresAt = now.plus(Duration.ofDays(1)))
        session.revoke(SessionRevokeReason.LOGOUT, now)

        assertThat(service.isActive(session.id)).isFalse()
    }

    @Test
    fun `없는 세션은 유효하지 않다`() {
        val sessionId = UUID.randomUUID()
        given(repository.findById(sessionId)).willReturn(Optional.empty())

        assertThat(service.isActive(sessionId)).isFalse()
    }

    private fun storedSession(expiresAt: Instant): AuthSession {
        val session =
            AuthSession(
                id = UUID.randomUUID(),
                memberId = 42L,
                refreshTokenHash = "0".repeat(64),
                createdAt = now.minus(Duration.ofDays(1)),
                expiresAt = expiresAt,
                absoluteExpiresAt = now.plus(Duration.ofDays(29)),
            )
        given(repository.findById(session.id)).willReturn(Optional.of(session))
        return session
    }

    private fun savedSession(): AuthSession {
        val captor = ArgumentCaptor.forClass(AuthSession::class.java)
        verify(repository).save(captor.capture())
        return captor.value
    }

    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    companion object {
        private const val SECRET = "test-jwt-secret-0123456789-0123456789-abcdef"
    }
}
