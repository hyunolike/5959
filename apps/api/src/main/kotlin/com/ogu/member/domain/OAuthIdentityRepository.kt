package com.ogu.member.domain

import org.springframework.data.jpa.repository.JpaRepository

interface OAuthIdentityRepository : JpaRepository<OAuthIdentity, Long> {
    fun findByProviderAndProviderUserId(
        provider: OAuthProvider,
        providerUserId: String,
    ): OAuthIdentity?
}
