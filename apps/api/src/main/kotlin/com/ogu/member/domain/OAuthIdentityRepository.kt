package com.ogu.member.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface OAuthIdentityRepository : JpaRepository<OAuthIdentity, Long> {
    fun findByProviderAndProviderUserId(
        provider: OAuthProvider,
        providerUserId: String,
    ): OAuthIdentity?

    /** 회원의 외부 계정 연결을 지운다(013 FR-013). 같은 외부 계정으로 다시 오면 새 회원이 된다. */
    @Modifying
    @Query("delete from OAuthIdentity i where i.memberId = :memberId")
    fun deleteAllByMemberId(
        @Param("memberId") memberId: Long,
    ): Int
}
