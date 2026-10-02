package com.ogu.member.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 한 기기에서의 로그인 상태. 테이블 정의와 유효 조건은 data-model.md `auth_session`을 따른다.
 * `id`는 JWT의 `sid` 클레임이다. refresh 토큰 원문은 저장하지 않고 SHA-256 해시(hex)만 둔다.
 */
@Entity
@Table(name = "auth_session")
class AuthSession(
    @Id
    private val id: UUID,
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: Long,
    refreshTokenHash: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    expiresAt: Instant,
    @Column(name = "absolute_expires_at", nullable = false, updatable = false)
    val absoluteExpiresAt: Instant,
) : Persistable<UUID> {
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = HASH_LENGTH)
    var refreshTokenHash: String = refreshTokenHash
        protected set

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "previous_refresh_token_hash", unique = true, length = HASH_LENGTH)
    var previousRefreshTokenHash: String? = null
        protected set

    @Column(name = "rotated_at")
    var rotatedAt: Instant? = null
        protected set

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = expiresAt
        protected set

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 20)
    var revokeReason: SessionRevokeReason? = null
        protected set

    // id를 애플리케이션이 정하므로, save()가 merge 전 SELECT를 하지 않도록 새 엔티티 여부를 직접 알려 준다.
    @Transient
    private var isNew: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = isNew

    /** 세션이 유효한 조건: `revoked_at IS NULL AND now < expires_at` */
    fun isActive(now: Instant): Boolean = revokedAt == null && now.isBefore(expiresAt)

    fun revoke(
        reason: SessionRevokeReason,
        now: Instant,
    ) {
        if (revokedAt != null) return
        revokedAt = now
        revokeReason = reason
    }

    /**
     * refresh 토큰을 교체한다. 직전 해시와 교체 시각을 남기고 만료를 `min(now + idleTtl, absolute_expires_at)`로 다시 잡는다.
     */
    fun rotate(
        newRefreshTokenHash: String,
        now: Instant,
        idleTtl: Duration,
    ) {
        previousRefreshTokenHash = refreshTokenHash
        refreshTokenHash = newRefreshTokenHash
        rotatedAt = now
        expiresAt = minOf(now.plus(idleTtl), absoluteExpiresAt)
    }

    /** [hash]가 직전 토큰이고 교체한 지 [grace] 이내(경계 포함)인가. */
    fun isWithinRotationGrace(
        hash: String,
        now: Instant,
        grace: Duration,
    ): Boolean {
        val rotated = rotatedAt ?: return false
        return hash == previousRefreshTokenHash && !now.isAfter(rotated.plus(grace))
    }

    @Suppress("UnusedPrivateMember") // JPA 콜백으로 Hibernate가 호출한다
    @PostLoad
    @PostPersist
    private fun markNotNew() {
        isNew = false
    }

    companion object {
        private const val HASH_LENGTH = 64

        fun start(
            memberId: Long,
            refreshTokenHash: String,
            now: Instant,
            idleTtl: Duration,
            absoluteTtl: Duration,
        ): AuthSession {
            val absoluteExpiresAt = now.plus(absoluteTtl)
            return AuthSession(
                id = UUID.randomUUID(),
                memberId = memberId,
                refreshTokenHash = refreshTokenHash,
                createdAt = now,
                expiresAt = minOf(now.plus(idleTtl), absoluteExpiresAt),
                absoluteExpiresAt = absoluteExpiresAt,
            )
        }
    }
}
