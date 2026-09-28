package com.ogu.member.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 로그인 실패 기록 한 행. 테이블 정의는 data-model.md `login_attempt`를 따른다.
 *
 * 쓰기는 모두 [LoginAttemptRepository]의 네이티브 쿼리로 한다(동시 실패를 한 문장으로 원자적으로 세기 위해서).
 * 이 엔티티는 차단 여부를 읽을 때만 쓴다.
 */
@Entity
@Table(name = "login_attempt")
class LoginAttempt(
    @Id
    @Column(name = "scope_key", length = 300)
    val scopeKey: String,
    @Column(name = "window_started_at", nullable = false)
    val windowStartedAt: Instant,
    @Column(name = "failure_count", nullable = false)
    val failureCount: Int,
    @Column(name = "blocked_until")
    val blockedUntil: Instant?,
) {
    fun isBlocked(now: Instant): Boolean = blockedUntil?.isAfter(now) ?: false
}
