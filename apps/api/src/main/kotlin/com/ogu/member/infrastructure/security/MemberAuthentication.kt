package com.ogu.member.infrastructure.security

import com.ogu.member.AuthenticatedMember
import org.springframework.security.authentication.AbstractAuthenticationToken

/**
 * JWT 검증을 마친 요청의 인증 정보. principal은 [AuthenticatedMember]다.
 */
class MemberAuthentication(
    val member: AuthenticatedMember,
    private val token: String,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): AuthenticatedMember = member

    override fun getCredentials(): String = token

    override fun getName(): String = member.memberId.toString()
}
