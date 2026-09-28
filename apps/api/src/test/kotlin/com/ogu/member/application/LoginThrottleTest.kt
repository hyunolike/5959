package com.ogu.member.application

import com.ogu.TestcontainersConfiguration
import com.ogu.member.domain.LoginAttemptRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T041: 로그인 실패 제한(FR-004, research R6, data-model.md `login_attempt`). 비밀번호 검증 전에 시도를 먼저 세는
 * 예약 방식을 실제 Postgres에서 확인한다(원자적 증가, 창, 차단 시간). 시간은 테스트가 움직이는 시계로 조절한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LoginThrottleTest {
    @Autowired
    lateinit var repository: LoginAttemptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val clock = MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))

    private val throttle by lazy { LoginThrottle(repository, clock) }

    private val email = "user-${UUID.randomUUID()}@example.com"
    private val ip = "203.0.113.${(1..254).random()}"

    @Test
    fun `IP와 이메일 키는 15분 안에 5번까지 시도할 수 있고 6번째부터 15분 동안 막힌다`() {
        repeat(5) { throttle.reserve(ip, email) }

        assertThat(throttledSeconds(ip, email)).isEqualTo(FIFTEEN_MINUTES)
        clock.advance(Duration.ofMinutes(15).minusSeconds(1))
        assertThat(throttledSeconds(ip, email)).isEqualTo(1)
        clock.advance(Duration.ofSeconds(1))
        assertThatCode { throttle.reserve(ip, email) }.doesNotThrowAnyException()
    }

    @Test
    fun `막힌 동안의 시도는 차단을 늘리지 않는다`() {
        repeat(5) { throttle.reserve(ip, email) }
        assertThat(throttledSeconds(ip, email)).isEqualTo(FIFTEEN_MINUTES)

        clock.advance(Duration.ofMinutes(10))
        assertThat(throttledSeconds(ip, email)).isEqualTo(FIVE_MINUTES)
        assertThat(throttledSeconds(ip, email)).isEqualTo(FIVE_MINUTES)
        assertThat(failureCount(LoginThrottle.ipEmailKey(ip, email))).isEqualTo(6)
    }

    @Test
    fun `IP와 이메일 키에서 막힌 시도는 이메일 키에 세지 않는다`() {
        repeat(5) { throttle.reserve(ip, email) }

        repeat(30) { throttledSeconds(ip, email) }

        assertThat(failureCount(LoginThrottle.emailKey(email))).isEqualTo(5)
    }

    @Test
    fun `IP와 이메일 키 차단은 다른 IP의 같은 이메일을 막지 않는다`() {
        repeat(5) { throttle.reserve(ip, email) }
        throttledSeconds(ip, email)

        assertThatCode { throttle.reserve("198.51.100.7", email) }.doesNotThrowAnyException()
    }

    @Test
    fun `이메일 키는 IP와 관계없이 1시간 안에 20번까지 시도할 수 있고 21번째부터 1시간 동안 모든 IP에서 막힌다`() {
        (1..20).forEach { throttle.reserve("198.51.100.$it", email) }

        assertThat(throttledSeconds("192.0.2.1", email)).isEqualTo(ONE_HOUR)
        assertThat(throttledSeconds("192.0.2.2", email)).isEqualTo(ONE_HOUR)
        clock.advance(Duration.ofHours(1))
        assertThatCode { throttle.reserve("192.0.2.1", email) }.doesNotThrowAnyException()
    }

    @Test
    fun `두 키가 모두 막혔으면 더 긴 남은 시간을 알려 준다`() {
        repeat(5) { throttle.reserve(ip, email) }
        throttledSeconds(ip, email)
        (1..15).forEach { throttle.reserve("198.51.100.$it", email) }
        throttledSeconds("198.51.100.99", email)

        assertThat(throttledSeconds(ip, email)).isEqualTo(ONE_HOUR)
    }

    @Test
    fun `남은 시간은 초 단위로 올림한다`() {
        repeat(5) { throttle.reserve(ip, email) }
        throttledSeconds(ip, email)

        clock.advance(Duration.ofMillis(1500))

        assertThat(throttledSeconds(ip, email)).isEqualTo(FIFTEEN_MINUTES - 1)
    }

    @Test
    fun `IP와 이메일 키는 15분 창이 지나면 시도 수가 초기화된다`() {
        repeat(5) { throttle.reserve(ip, email) }

        clock.advance(Duration.ofMinutes(15))
        throttle.reserve(ip, email)

        assertThat(failureCount(LoginThrottle.ipEmailKey(ip, email))).isEqualTo(1)
        assertThat(windowStartedAt(LoginThrottle.ipEmailKey(ip, email))).isEqualTo(clock.instant())
        repeat(4) { throttle.reserve(ip, email) }
        assertThat(throttledSeconds(ip, email)).isEqualTo(FIFTEEN_MINUTES)
    }

    @Test
    fun `이메일 키는 1시간 창이 지나면 시도 수가 초기화된다`() {
        (1..20).forEach { throttle.reserve("198.51.100.$it", email) }

        clock.advance(Duration.ofHours(1))
        throttle.reserve("198.51.100.21", email)

        assertThat(failureCount(LoginThrottle.emailKey(email))).isEqualTo(1)
    }

    @Test
    fun `창 안에서는 시간이 조금 지나도 시도 수가 이어진다`() {
        repeat(4) { throttle.reserve(ip, email) }

        clock.advance(Duration.ofMinutes(14).plusSeconds(59))
        throttle.reserve(ip, email)

        assertThat(failureCount(LoginThrottle.ipEmailKey(ip, email))).isEqualTo(5)
        assertThat(throttledSeconds(ip, email)).isEqualTo(FIFTEEN_MINUTES)
    }

    @Test
    fun `차단이 끝난 뒤 다시 시도하면 새 창에서 1부터 세고 차단이 풀린다`() {
        repeat(5) { throttle.reserve(ip, email) }
        throttledSeconds(ip, email)
        clock.advance(Duration.ofMinutes(15))

        throttle.reserve(ip, email)

        assertThat(failureCount(LoginThrottle.ipEmailKey(ip, email))).isEqualTo(1)
        assertThat(blockedUntil(LoginThrottle.ipEmailKey(ip, email))).isNull()
    }

    @Test
    fun `로그인에 성공하면 IP와 이메일 키를 지우고 이메일 키는 성공한 시도 하나만 뺀다`() {
        repeat(4) { throttle.reserve(ip, email) }

        throttle.recordSuccess(ip, email)

        assertThat(rowExists(LoginThrottle.ipEmailKey(ip, email))).isFalse()
        assertThat(failureCount(LoginThrottle.emailKey(email))).isEqualTo(3)
    }

    @Test
    fun `이메일 키 창이 이미 지났으면 성공해도 이메일 키를 줄이지 않는다`() {
        throttle.reserve(ip, email)
        clock.advance(Duration.ofHours(1))

        throttle.recordSuccess(ip, email)

        assertThat(failureCount(LoginThrottle.emailKey(email))).isEqualTo(1)
    }

    @Test
    fun `동시에 들어온 시도 10건은 정확히 10으로 센다`() {
        val results = runConcurrently(10) { throttle.reserve("198.51.100.$it", email) }

        assertThat(results).allMatch { it == null }
        assertThat(failureCount(LoginThrottle.emailKey(email))).isEqualTo(10)
    }

    @Test
    fun `한 IP에서 동시에 들어온 시도 20건 중 정확히 5건만 통과한다`() {
        val results = runConcurrently(20) { throttle.reserve(ip, email) }

        assertThat(results.count { it == null }).isEqualTo(5)
        assertThat(results.filterNotNull()).hasSize(15).allMatch { it.errorCode == ErrorCode.LOGIN_THROTTLED }
        assertThat(failureCount(LoginThrottle.emailKey(email))).isEqualTo(5)
    }

    @Test
    fun `키는 정규화한 IP와 이메일의 SHA-256 hex로 만들어 길이가 고정된다`() {
        val ipHash = sha256Hex("203.0.113.1")
        val emailHash = sha256Hex("a@example.com")

        assertThat(LoginThrottle.ipEmailKey("203.0.113.1", "a@example.com"))
            .isEqualTo("ip:$ipHash|email:$emailHash")
        assertThat(LoginThrottle.emailKey("a@example.com")).isEqualTo("email:$emailHash")
        val longest =
            LoginThrottle.ipEmailKey(
                "0000:0000:0000:0000:0000:ffff:192.168.100.200",
                "a".repeat(242) + "@example.com",
            )
        assertThat(longest).hasSize(3 + 64 + 7 + 64)
    }

    @Test
    fun `254자 이메일과 가장 긴 IPv6 표기도 정상적으로 세고 막는다`() {
        val longEmail = "a".repeat(230) + UUID.randomUUID().toString().take(12) + "@example.com"
        val longIp = "0000:0000:0000:0000:0000:ffff:192.168.100.200"
        assertThat(longEmail).hasSize(254)

        repeat(5) { throttle.reserve(longIp, longEmail) }

        assertThat(throttledSeconds(longIp, longEmail)).isEqualTo(FIFTEEN_MINUTES)
    }

    @Test
    fun `정리 작업은 하루 넘게 쓰이지 않은 행만 지운다`() {
        // 다른 테스트의 행에 닿지 않도록 먼 과거 시각으로 이 테스트만의 행을 만들고, 그 시각 기준으로 정리한다.
        val base = Instant.parse("2000-01-03T00:00:00Z")
        val prefix = "cleanup-test:${UUID.randomUUID()}:"
        insertAttempt("${prefix}stale", windowStartedAt = base.minus(Duration.ofDays(2)), blockedUntil = null)
        insertAttempt(
            "${prefix}stale-blocked",
            windowStartedAt = base.minus(Duration.ofDays(2)),
            blockedUntil = base.minus(Duration.ofDays(1)).minusSeconds(1),
        )
        insertAttempt(
            "${prefix}recently-blocked",
            windowStartedAt = base.minus(Duration.ofDays(2)),
            blockedUntil = base.minus(Duration.ofHours(23)),
        )
        insertAttempt("${prefix}recent", windowStartedAt = base.minus(Duration.ofHours(23)), blockedUntil = null)
        val cleanup = LoginAttemptCleanupJob(repository, Clock.fixed(base, ZoneOffset.UTC))

        val deleted = cleanup.deleteStaleAttempts()

        assertThat(deleted).isEqualTo(2)
        assertThat(rowExists("${prefix}stale")).isFalse()
        assertThat(rowExists("${prefix}stale-blocked")).isFalse()
        assertThat(rowExists("${prefix}recently-blocked")).isTrue()
        assertThat(rowExists("${prefix}recent")).isTrue()
        jdbcTemplate.update("delete from login_attempt where scope_key like ?", "$prefix%")
    }

    private fun runConcurrently(
        threads: Int,
        action: (Int) -> Unit,
    ): List<BusinessException?> {
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map { n ->
                    executor.submit(
                        Callable {
                            ready.countDown()
                            start.await()
                            try {
                                action(n)
                                null
                            } catch (e: BusinessException) {
                                e
                            }
                        },
                    )
                }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun insertAttempt(
        key: String,
        windowStartedAt: Instant,
        blockedUntil: Instant?,
    ) {
        jdbcTemplate.update(
            "insert into login_attempt (scope_key, window_started_at, failure_count, blocked_until) " +
                "values (?, ?, 1, ?)",
            key,
            Timestamp.from(windowStartedAt),
            blockedUntil?.let { Timestamp.from(it) },
        )
    }

    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    private fun throttledSeconds(
        ip: String,
        email: String,
    ): Int? {
        val e = catchThrowableOfType(BusinessException::class.java) { throttle.reserve(ip, email) }
        assertThat(e).describedAs("차단되어야 한다").isNotNull()
        assertThat(e.errorCode).isEqualTo(ErrorCode.LOGIN_THROTTLED)
        return e.retryAfterSeconds
    }

    private fun failureCount(key: String): Int? =
        jdbcTemplate.queryForObject("select failure_count from login_attempt where scope_key = ?", Int::class.java, key)

    private fun windowStartedAt(key: String): Instant? =
        jdbcTemplate.queryForObject(
            "select window_started_at from login_attempt where scope_key = ?",
            Instant::class.java,
            key,
        )

    private fun blockedUntil(key: String): Instant? =
        jdbcTemplate.queryForObject(
            "select blocked_until from login_attempt where scope_key = ?",
            Instant::class.java,
            key,
        )

    private fun rowExists(key: String): Boolean =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "select count(*) from login_attempt where scope_key = ?",
                Int::class.java,
                key,
            ),
        ) > 0

    /** 테스트가 시간을 앞으로 옮길 수 있는 시계. */
    class MutableClock(
        @Volatile private var now: Instant,
    ) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    companion object {
        private const val FIFTEEN_MINUTES = 15 * 60
        private const val FIVE_MINUTES = 5 * 60
        private const val ONE_HOUR = 60 * 60
    }
}
