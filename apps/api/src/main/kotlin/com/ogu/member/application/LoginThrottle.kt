package com.ogu.member.application

import com.ogu.member.domain.LoginAttemptRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat

/**
 * 로그인 실패 제한(FR-004, research R6). 창, 한도, 차단 값은 data-model.md `login_attempt` 표를 따른다.
 *
 * | 키 | 창 | 한도 | 차단 |
 * |---|---|---|---|
 * | `ip:{sha256(ip)}|email:{sha256(email)}` | 15분 | 5회 | 15분 |
 * | `email:{sha256(email)}` | 1시간 | 20회 | 1시간 |
 *
 * 비밀번호를 검증하기 전에 [reserve]로 시도를 먼저 센다(예약). 검증과 기록 사이의 틈이 없어 동시에 보낸 요청도 한도만큼만
 * 비밀번호 검증에 닿는다. 검증에 실패하면 예약이 곧 실패 기록이고, 성공하면 [recordSuccess]가 되돌린다.
 *
 * IP와 이메일은 정규화된 값이어야 한다. 가입되지 않은 이메일도 같은 규칙으로 센다(계정 존재 여부를 드러내지 않기 위해).
 */
@Component
class LoginThrottle(
    private val repository: LoginAttemptRepository,
    private val clock: Clock,
) {
    /**
     * 시도 하나를 센다. 두 키 중 하나라도 이미 막혀 있거나 이번 시도로 한도를 넘으면 `429 LOGIN_THROTTLED`.
     * 남은 시간은 막힌 키 중 더 긴 쪽을 초 단위로 올림한다.
     *
     * IP+이메일 키에서 막힌 시도는 이메일 키에 세지 않는다. 한 출처가 막힌 뒤 계속 보내는 요청만으로 계정 전체가
     * 막히지 않게 하기 위해서다.
     */
    fun reserve(
        clientIp: String,
        email: String,
    ) {
        val now = now()
        val ipEmail = reserve(ipEmailKey(clientIp, email), IP_EMAIL, now)
        val emailOnly =
            if (ipEmail.isBlocked(now)) {
                repository.find(emailKey(email))
            } else {
                reserve(emailKey(email), EMAIL, now)
            }
        val blockedUntil =
            listOfNotNull(ipEmail, emailOnly)
                .filter { it.isBlocked(now) }
                .mapNotNull { it.blockedUntil }
                .maxOrNull()
                ?: return
        throw BusinessException(ErrorCode.LOGIN_THROTTLED, retryAfterSeconds = secondsUntil(now, blockedUntil))
    }

    /**
     * 로그인 성공. IP+이메일 키는 지우고, 이메일 키는 이번 성공으로 센 시도 하나만 뺀다. 이메일 키를 지우지 않는 것은
     * 여러 출처의 공격이 성공 한 번으로 초기화되지 않게 하기 위해서다.
     */
    fun recordSuccess(
        clientIp: String,
        email: String,
    ) {
        val now = now()
        repository.delete(ipEmailKey(clientIp, email))
        repository.decrementIfWindowCurrent(emailKey(email), windowExpiredBefore = now.minus(EMAIL.window))
    }

    /**
     * `login_attempt.blocked_until`은 Postgres `timestamptz`라 마이크로초까지만 담고, 그보다 더 정밀한 값은
     * 반올림해서 저장한다(research: 버그 재현 R6-bis). [Clock]이 마이크로초보다 더 정밀한 시각을 주는 환경(CI의
     * Linux 등)에서 이걸 그대로 `blockedUntil = now.plus(block)` 계산에 쓰면, DB에 썼다가 돌려받은
     * `blocked_until`이 원래 값보다 최대 1마이크로초 밀려 올라갈 수 있다. 그러면 [secondsUntil]의
     * `Duration.between(now, blockedUntil)`에 0이 아닌 나노초가 남아, 정확히 창 길이(예: 900초)여야 할 남은
     * 시간에 올림 규칙이 불필요하게 1초를 더해 버린다(간헐적으로 `Retry-After`/`retryAfterSeconds`가 901처럼
     * 나오는 원인). `now`를 DB 저장 정밀도(마이크로초)로 미리 잘라 두면 `blockedUntil`도 항상 마이크로초에
     * 딱 맞아 왕복 과정에서 반올림이 전혀 일어나지 않는다.
     */
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun reserve(
        key: String,
        rule: Rule,
        now: Instant,
    ) = repository.reserve(
        scopeKey = key,
        now = now,
        windowExpiredBefore = now.minus(rule.window),
        failureLimit = rule.limit,
        blockedUntil = now.plus(rule.block),
    )

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

        /** 키 길이를 고정하려고 IP와 이메일을 SHA-256 hex로 바꿔 쓴다(`scope_key`는 varchar(300)). */
        fun ipEmailKey(
            clientIp: String,
            email: String,
        ): String = "ip:${sha256Hex(clientIp)}|email:${sha256Hex(email)}"

        fun emailKey(email: String): String = "email:${sha256Hex(email)}"

        private fun sha256Hex(value: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    }
}
