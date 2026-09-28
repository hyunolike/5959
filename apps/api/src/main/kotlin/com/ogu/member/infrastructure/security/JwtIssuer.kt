package com.ogu.member.infrastructure.security

import com.ogu.member.infrastructure.config.AuthProperties
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * access 토큰(HS256 JWT)을 발급한다. 클레임은 data-model.md의 "JWT access 토큰 클레임" 표를 따른다.
 * 검증은 [decoder]가 만든 디코더로 한다. 서명, 발급자(`iss=ogu-api`), 만료를 확인한다.
 */
@Component
class JwtIssuer(
    properties: AuthProperties,
    private val clock: Clock,
) {
    private val ttl: Duration = properties.jwt.accessTokenTtl
    private val encoder: JwtEncoder =
        NimbusJwtEncoder
            .withSecretKey(secretKey(properties.jwt.secret))
            .algorithm(MacAlgorithm.HS256)
            .build()

    fun issue(
        memberId: Long,
        sessionId: UUID,
        onboarded: Boolean,
    ): AccessToken {
        val issuedAt = clock.instant()
        val expiresAt = issuedAt.plus(ttl)
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(ISSUER)
                .subject(memberId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(CLAIM_SESSION_ID, sessionId.toString())
                .claim(CLAIM_ONBOARDED, onboarded)
                .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims))
        return AccessToken(value = token.tokenValue, expiresAt = expiresAt)
    }

    companion object {
        const val ISSUER = "ogu-api"
        const val CLAIM_SESSION_ID = "sid"
        const val CLAIM_ONBOARDED = "onboarded"

        /** HS256은 256비트 이상의 키가 필요하다(RFC 7518 3.2). */
        private const val MIN_SECRET_BYTES = 32

        fun secretKey(secret: String): SecretKey {
            val bytes = secret.toByteArray(Charsets.UTF_8)
            require(bytes.size >= MIN_SECRET_BYTES) {
                "ogu.auth.jwt.secret은 UTF-8 기준 ${MIN_SECRET_BYTES}바이트 이상이어야 합니다."
            }
            return SecretKeySpec(bytes, "HmacSHA256")
        }

        /**
         * HS256 서명, `iss=ogu-api`, `exp`(필수)를 검증하는 디코더. 발급과 검증을 같은 서버가 하므로 시계 오차 허용은 두지 않는다.
         */
        fun decoder(
            secret: String,
            clock: Clock,
        ): JwtDecoder {
            val decoder =
                NimbusJwtDecoder
                    .withSecretKey(secretKey(secret))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build()
            val timestamp =
                JwtTimestampValidator(Duration.ZERO).apply {
                    setClock(clock)
                    // 7.1 기본값은 exp가 없어도 통과시킨다. exp 없는 토큰은 영원히 유효하므로 거절한다.
                    setAllowEmptyExpiryClaim(false)
                }
            decoder.setJwtValidator(
                JwtValidators.createDefaultWithValidators(
                    timestamp,
                    JwtIssuerValidator(ISSUER),
                ),
            )
            return decoder
        }
    }
}

data class AccessToken(
    val value: String,
    val expiresAt: Instant,
)
