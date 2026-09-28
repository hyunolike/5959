package com.ogu.member.application

import com.ogu.member.domain.LoginAttemptRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 로그인 실패 제한(FR-004, research R6). 창, 한도, 차단 값은 data-model.md `login_attempt` 표를 따른다.
 *
 * | 키 | 창 | 한도 | 차단 |
 * |---|---|---|---|
 * | `ip:{ip}|email:{email}` | 15분 | 5회 | 15분 |
 * | `email:{email}` | 1시간 | 20회 | 1시간 |
 *
 * [email]은 정규화된 값이어야 한다. 가입되지 않은 이메일도 같은 규칙으로 센다(계정 존재 여부를 드러내지 않기 위해).
 */
@Component
class LoginThrottle(
    private val repository: LoginAttemptRepository,
    private val clock: Clock,
) {
    /** 두 키 중 하나라도 막혀 있으면 `429 LOGIN_THROTTLED`. 남은 시간은 더 긴 쪽을 초 단위로 올림한다. */
    fun checkNotBlocked(
        clientIp: String,
        email: String,
    ) {
        val now = clock.instant()
        val blockedUntil =
            repository
                .findAllById(listOf(ipEmailKey(clientIp, email), emailKey(email)))
                .filter { it.isBlocked(now) }
                .mapNotNull { it.blockedUntil }
                .maxOrNull()
                ?: return
        throw BusinessException(ErrorCode.LOGIN_THROTTLED, retryAfterSeconds = secondsUntil(now, blockedUntil))
    }

    fun recordFailure(
        clientIp: String,
        email: String,
    ) {
        val now = clock.instant()
        record(ipEmailKey(clientIp, email), IP_EMAIL, now)
        record(emailKey(email), EMAIL, now)
    }

    /** 성공하면 IP+이메일 키만 지운다. 이메일 키는 여러 출처의 공격이 성공 한 번으로 초기화되지 않도록 남긴다. */
    fun recordSuccess(
        clientIp: String,
        email: String,
    ) {
        repository.deleteById(ipEmailKey(clientIp, email))
    }

    private fun record(
        key: String,
        rule: Rule,
        now: Instant,
    ) {
        repository.recordFailure(
            scopeKey = key,
            now = now,
            windowExpiredBefore = now.minus(rule.window),
            failureLimit = rule.limit,
            blockedUntil = now.plus(rule.block),
        )
    }

    private fun secondsUntil(
        now: Instant,
        until: Instant,
    ): Int {
        val remaining = Duration.between(now, until)
        val seconds = remaining.seconds + if (remaining.nano > 0) 1 else 0
        return seconds.toInt()
    }

    private data class Rule(
        val window: Duration,
        val limit: Int,
        val block: Duration,
    )

    companion object {
        private val IP_EMAIL = Rule(window = Duration.ofMinutes(15), limit = 5, block = Duration.ofMinutes(15))
        private val EMAIL = Rule(window = Duration.ofHours(1), limit = 20, block = Duration.ofHours(1))

        fun ipEmailKey(
            clientIp: String,
            email: String,
        ): String = "ip:$clientIp|email:$email"

        fun emailKey(email: String): String = "email:$email"
    }
}
