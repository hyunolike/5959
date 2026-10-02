package com.ogu.member.domain

import java.time.Instant

/**
 * 시도를 하나 센 뒤의 `login_attempt` 행 상태(data-model.md `login_attempt`). 쓰기는 모두 [LoginAttemptRepository]의
 * 네이티브 SQL로 한다. 동시에 들어온 시도를 한 문장으로 원자적으로 세기 위해서라 JPA 엔티티로 두지 않는다.
 */
data class LoginAttempt(
    val failureCount: Int,
    val blockedUntil: Instant?,
) {
    fun isBlocked(now: Instant): Boolean = blockedUntil?.isAfter(now) ?: false
}
