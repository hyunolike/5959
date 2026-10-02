package com.ogu.member.infrastructure.security

import com.ogu.member.infrastructure.config.AuthProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class JwtIssuerTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val sessionId = UUID.fromString("7b0f7c3e-0d1c-4f55-9b2a-6a4d7a9f1c11")

    @Test
    fun `발급한 토큰을 디코더로 검증하면 클레임이 같다`() {
        val issuer = JwtIssuer(properties(SECRET), clock)

        val token = issuer.issue(memberId = 42L, sessionId = sessionId, onboarded = true)
        val jwt = JwtIssuer.decoder(SECRET, clock).decode(token.value)

        assertThat(jwt.subject).isEqualTo("42")
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString())
        assertThat(jwt.getClaim<Boolean>("onboarded")).isTrue()
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("ogu-api")
        assertThat(jwt.headers["alg"]).isEqualTo("HS256")
        assertThat(jwt.issuedAt).isEqualTo(now)
        assertThat(jwt.expiresAt).isEqualTo(now.plus(Duration.ofMinutes(15)))
        assertThat(token.expiresAt).isEqualTo(now.plus(Duration.ofMinutes(15)))
    }

    @Test
    fun `onboarded가 false인 토큰도 클레임을 그대로 담는다`() {
        val issuer = JwtIssuer(properties(SECRET), clock)

        val token = issuer.issue(memberId = 7L, sessionId = sessionId, onboarded = false)
        val jwt = JwtIssuer.decoder(SECRET, clock).decode(token.value)

        assertThat(jwt.getClaim<Boolean>("onboarded")).isFalse()
    }

    @Test
    fun `만료된 토큰은 거부된다`() {
        val issuedClock = Clock.fixed(now.minus(Duration.ofMinutes(16)), ZoneOffset.UTC)
        val token = JwtIssuer(properties(SECRET), issuedClock).issue(42L, sessionId, true)

        assertThatThrownBy { JwtIssuer.decoder(SECRET, clock).decode(token.value) }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `다른 비밀키로 서명한 토큰은 거부된다`() {
        val token = JwtIssuer(properties(OTHER_SECRET), clock).issue(42L, sessionId, true)

        assertThatThrownBy { JwtIssuer.decoder(SECRET, clock).decode(token.value) }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `발급자가 ogu-api가 아닌 토큰은 거부된다`() {
        val foreign =
            NimbusJwtEncoder
                .withSecretKey(JwtIssuer.secretKey(SECRET))
                .build()
                .encode(
                    JwtEncoderParameters.from(
                        JwsHeader
                            .with(MacAlgorithm.HS256)
                            .build(),
                        JwtClaimsSet
                            .builder()
                            .issuer("someone-else")
                            .subject("42")
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(60))
                            .build(),
                    ),
                ).tokenValue

        assertThatThrownBy { JwtIssuer.decoder(SECRET, clock).decode(foreign) }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `올바른 키로 서명했어도 exp가 없는 토큰은 거부된다`() {
        val withoutExpiry =
            NimbusJwtEncoder
                .withSecretKey(JwtIssuer.secretKey(SECRET))
                .build()
                .encode(
                    JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet
                            .builder()
                            .issuer("ogu-api")
                            .subject("42")
                            .issuedAt(now)
                            .claim("sid", sessionId.toString())
                            .claim("onboarded", true)
                            .build(),
                    ),
                ).tokenValue

        assertThatThrownBy { JwtIssuer.decoder(SECRET, clock).decode(withoutExpiry) }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `alg=none 토큰은 거부된다`() {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload =
            encoder.encodeToString(
                """{"iss":"ogu-api","sub":"42","sid":"$sessionId","onboarded":true,"exp":${now.epochSecond + 60}}"""
                    .toByteArray(),
            )

        assertThatThrownBy { JwtIssuer.decoder(SECRET, clock).decode("$header.$payload.") }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `같은 비밀키라도 HS512로 서명한 토큰은 거부된다`() {
        val hs512 =
            NimbusJwtEncoder
                .withSecretKey(SecretKeySpec(LONG_SECRET.toByteArray(), "HmacSHA512"))
                .algorithm(MacAlgorithm.HS512)
                .build()
                .encode(
                    JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS512).build(),
                        JwtClaimsSet
                            .builder()
                            .issuer("ogu-api")
                            .subject("42")
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(60))
                            .build(),
                    ),
                ).tokenValue

        assertThatThrownBy { JwtIssuer.decoder(LONG_SECRET, clock).decode(hs512) }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `32바이트보다 짧은 비밀키는 받지 않는다`() {
        assertThatThrownBy { JwtIssuer(properties("too-short-secret"), clock) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun properties(secret: String) =
        AuthProperties(
            jwt = AuthProperties.Jwt(secret = secret, accessTokenTtl = Duration.ofMinutes(15)),
            session =
                AuthProperties.Session(
                    idleTtl = Duration.ofDays(14),
                    absoluteTtl = Duration.ofDays(30),
                    rotationGrace = Duration.ofSeconds(30),
                ),
        )

    companion object {
        private const val SECRET = "test-jwt-secret-0123456789-0123456789-abcdef"
        private const val OTHER_SECRET = "another-jwt-secret-9876543210-9876543210-xyz"

        /** HS512 서명에는 64바이트 이상의 키가 필요하다. */
        private const val LONG_SECRET = "long-jwt-secret-for-hs512-0123456789-0123456789-0123456789-abcdefgh"
    }
}
