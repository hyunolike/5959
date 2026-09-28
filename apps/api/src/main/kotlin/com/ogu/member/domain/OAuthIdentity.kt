package com.ogu.member.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 외부 계정과 회원의 연결. 컬럼과 제약은 data-model.md `oauth_identity`를 따른다.
 * 한 외부 계정은 한 회원에게만, 한 회원은 제공자마다 한 계정만 연결된다(두 유일 제약).
 */
@Entity
@Table(name = "oauth_identity")
class OAuthIdentity private constructor(
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 10)
    val provider: OAuthProvider,
    @Column(name = "provider_user_id", nullable = false, updatable = false, length = 255)
    val providerUserId: String,
    /** 제공자가 준 이메일(참고용). 검증 여부와 관계없이 정규화해 둔다. */
    @Column(name = "email", length = 254)
    val email: String?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0L
        protected set

    companion object {
        fun link(
            memberId: Long,
            provider: OAuthProvider,
            providerUserId: String,
            email: String?,
            now: Instant,
        ): OAuthIdentity = OAuthIdentity(memberId, provider, providerUserId, email, now)
    }
}
