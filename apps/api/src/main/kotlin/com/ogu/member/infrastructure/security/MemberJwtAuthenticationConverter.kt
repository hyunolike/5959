package com.ogu.member.infrastructure.security

import com.ogu.member.AuthenticatedMember
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import java.util.UUID

/**
 * 서명과 발급자, 만료 검증을 통과한 JWT의 클레임을 [AuthenticatedMember]로 바꾼다.
 * 클레임이 빠졌거나 형식이 틀리면 인증 실패(401)로 처리한다.
 */
class MemberJwtAuthenticationConverter : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val memberId = jwt.subject?.toLongOrNull() ?: invalid("sub")
        val sessionId =
            jwt
                .getClaimAsString(JwtIssuer.CLAIM_SESSION_ID)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: invalid(JwtIssuer.CLAIM_SESSION_ID)
        val onboarded = jwt.claims[JwtIssuer.CLAIM_ONBOARDED] as? Boolean ?: invalid(JwtIssuer.CLAIM_ONBOARDED)
        val member = AuthenticatedMember(memberId = memberId, sessionId = sessionId, onboarded = onboarded)
        return MemberAuthentication(member, jwt.tokenValue)
    }

    private fun invalid(claim: String): Nothing = throw InvalidBearerTokenException("access 토큰의 $claim 클레임이 올바르지 않습니다.")
}
