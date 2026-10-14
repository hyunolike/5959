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
 * 비밀번호 재설정 요청의 남용 확인(015 FR-008, FR-009, research R4). 로그인 시도 기록(`login_attempt`)에 키 두 가지를
 * 더해 쓴다. 그 테이블이 "창 안의 수를 세고 넘으면 막는다"를 한 문장으로 하고, 정리 주기도 이미 있다. IP와 이메일의
 * 원문은 저장하지 않는다.
 *
 * - 곳(IP): 한 시간에 [SOURCE]번까지. 넘으면 `429 PASSWORD_RESET_THROTTLED`와 남은 시간이다.
 * - 이메일: 한 시간에 [EMAIL]번까지. 넘어도 예외를 던지지 않는다. 부르는 쪽이 응답을 같게 하고 메일만 보내지 않는다.
 *   여기서 429를 주면 한도가 이메일마다 따로 세어진다는 것으로 가입 여부를 떠볼 길이 생긴다.
 */
@Component
class PasswordResetThrottle(
    private val repository: LoginAttemptRepository,
    private val clock: Clock,
) {
    /** 요청 하나를 센다. 이 곳의 한도를 넘었으면 429다. */
    fun reserveSource(clientIp: String) {
        val now = now()
        val attempt = reserve("reset-ip:" + sha256Hex(clientIp), SOURCE, now)
        val blockedUntil = attempt.blockedUntil?.takeIf { attempt.isBlocked(now) } ?: return
        val seconds =
            Duration
                .between(now, blockedUntil)
                .toSeconds()
                .coerceAtLeast(1)
                .toInt()
        throw BusinessException(ErrorCode.PASSWORD_RESET_THROTTLED, retryAfterSeconds = seconds)
    }

    /** 이 이메일로 메일을 보내도 되는가. 가입 여부와 상관없이 센다. */
    fun allowsEmail(email: String): Boolean {
        val now = now()
        return !reserve("reset-email:" + sha256Hex(email), EMAIL, now).isBlocked(now)
    }

    private fun reserve(
        key: String,
        rule: Rule,
        now: Instant,
    ) = repository.reserve(
        scopeKey = key,
        now = now,
        windowExpiredBefore = now.minus(WINDOW),
        failureLimit = rule.limit,
        blockedUntil = now.plus(WINDOW),
    )

    // DB의 정밀도(마이크로초)로 잘라 둔다. 남은 시간에 1초가 더 붙지 않는다(LoginThrottle과 같은 까닭)
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    private data class Rule(
        val limit: Int,
    )

    private companion object {
        val WINDOW: Duration = Duration.ofHours(1)
        val SOURCE = Rule(limit = 10)
        val EMAIL = Rule(limit = 3)
    }
}
