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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T041: 로그인 실패 제한(FR-004, research R6, data-model.md `login_attempt`). 실제 Postgres에서 원자적 증가와
 * 창, 차단 시간을 확인한다. 시간은 테스트가 움직이는 시계로 조절한다.
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
    fun `IP와 이메일 키는 15분 안에 5번 실패하면 15분 동안 막힌다`() {
        repeat(4) { throttle.recordFailure(ip, email) }
        assertThatCode { throttle.checkNotBlocked(ip, email) }.doesNotThrowAnyException()

        throttle.recordFailure(ip, email)

        assertThat(throttledSeconds(ip, email)).isEqualTo(FIFTEEN_MINUTES)
        clock.advance(Duration.ofMinutes(15).minusSeconds(1))
        assertThat(throttledSeconds(ip, email)).isEqualTo(1)
        clock.advance(Duration.ofSeconds(1))
        assertThatCode { throttle.checkNotBlocked(ip, email) }.doesNotThrowAnyException()
    }

    @Test
    fun `IP와 이메일 키 차단은 다른 IP의 같은 이메일을 막지 않는다`() {
        repeat(5) { throttle.recordFailure(ip, email) }

        assertThat(throttledSeconds(ip, email)).isEqualTo(FIFTEEN_MINUTES)
        assertThatCode { throttle.checkNotBlocked("198.51.100.7", email) }.doesNotThrowAnyException()
    }

    @Test
    fun `이메일 키는 IP와 관계없이 1시간 안에 20번 실패하면 1시간 동안 모든 IP에서 막힌다`() {
        (1..19).forEach { throttle.recordFailure("198.51.100.$it", email) }
        assertThatCode { throttle.checkNotBlocked("192.0.2.1", email) }.doesNotThrowAnyException()

        throttle.recordFailure("198.51.100.20", email)

        assertThat(throttledSeconds("192.0.2.1", email)).isEqualTo(ONE_HOUR)
        assertThat(throttledSeconds("192.0.2.2", email)).isEqualTo(ONE_HOUR)
        clock.advance(Duration.ofHours(1))
        assertThatCode { throttle.checkNotBlocked("192.0.2.1", email) }.doesNotThrowAnyException()
    }

    @Test
    fun `두 키가 모두 막혔으면 더 긴 남은 시간을 알려 준다`() {
        (1..15).forEach { throttle.recordFailure("198.51.100.$it", email) }
        repeat(5) { throttle.recordFailure(ip, email) }

        assertThat(throttledSeconds(ip, email)).isEqualTo(ONE_HOUR)
    }

    @Test
    fun `남은 시간은 초 단위로 올림한다`() {
        repeat(5) { throttle.recordFailure(ip, email) }

        clock.advance(Duration.ofMillis(1500))

        assertThat(throttledSeconds(ip, email)).isEqualTo(FIFTEEN_MINUTES - 1)
    }

    @Test
    fun `IP와 이메일 키는 15분 창이 지나면 실패 수가 초기화된다`() {
        repeat(4) { throttle.recordFailure(ip, email) }

        clock.advance(Duration.ofMinutes(15))
        throttle.recordFailure(ip, email)

        assertThat(failureCount(LoginThrottle.ipEmailKey(ip, email))).isEqualTo(1)
        assertThat(windowStartedAt(LoginThrottle.ipEmailKey(ip, email))).isEqualTo(clock.instant())
        repeat(3) { throttle.recordFailure(ip, email) }
        assertThatCode { throttle.checkNotBlocked(ip, email) }.doesNotThrowAnyException()
    }

    @Test
    fun `이메일 키는 1시간 창이 지나면 실패 수가 초기화된다`() {
        (1..19).forEach { throttle.recordFailure("198.51.100.$it", email) }

        clock.advance(Duration.ofHours(1))
        throttle.recordFailure("198.51.100.20", email)

        assertThat(failureCount(LoginThrottle.emailKey(email))).isEqualTo(1)
        assertThatCode { throttle.checkNotBlocked("192.0.2.1", email) }.doesNotThrowAnyException()
    }

    @Test
    fun `창 안에서는 시간이 조금 지나도 실패 수가 이어진다`() {
        repeat(4) { throttle.recordFailure(ip, email) }

        clock.advance(Duration.ofMinutes(14).plusSeconds(59))
        throttle.recordFailure(ip, email)

        assertThat(failureCount(LoginThrottle.ipEmailKey(ip, email))).isEqualTo(5)
        assertThat(throttledSeconds(ip, email)).isEqualTo(FIFTEEN_MINUTES)
    }

    @Test
    fun `차단이 끝난 뒤 다시 실패하면 새 창에서 1부터 센다`() {
        repeat(5) { throttle.recordFailure(ip, email) }
        clock.advance(Duration.ofMinutes(15))

        throttle.recordFailure(ip, email)

        assertThat(failureCount(LoginThrottle.ipEmailKey(ip, email))).isEqualTo(1)
        assertThat(blockedUntil(LoginThrottle.ipEmailKey(ip, email))).isNull()
        assertThatCode { throttle.checkNotBlocked(ip, email) }.doesNotThrowAnyException()
    }

    @Test
    fun `로그인에 성공하면 IP와 이메일 키만 지우고 이메일 키는 남긴다`() {
        repeat(3) { throttle.recordFailure(ip, email) }

        throttle.recordSuccess(ip, email)

        assertThat(rowExists(LoginThrottle.ipEmailKey(ip, email))).isFalse()
        assertThat(failureCount(LoginThrottle.emailKey(email))).isEqualTo(3)
    }

    @Test
    fun `동시에 들어온 실패 10건은 정확히 10으로 센다`() {
        val threads = 10
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    executor.submit(
                        Callable {
                            ready.countDown()
                            start.await()
                            throttle.recordFailure(ip, email)
                        },
                    )
                }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(failureCount(LoginThrottle.ipEmailKey(ip, email))).isEqualTo(10)
        assertThat(failureCount(LoginThrottle.emailKey(email))).isEqualTo(10)
    }

    @Test
    fun `키 형식은 data-model의 ip-email 키와 email 키를 따른다`() {
        assertThat(LoginThrottle.ipEmailKey("203.0.113.1", "a@example.com"))
            .isEqualTo("ip:203.0.113.1|email:a@example.com")
        assertThat(LoginThrottle.emailKey("a@example.com")).isEqualTo("email:a@example.com")
    }

    @Test
    fun `정리 작업은 하루 넘게 쓰이지 않은 행만 지운다`() {
        val staleEmail = "stale-${UUID.randomUUID()}@example.com"
        val blockedEmail = "blocked-${UUID.randomUUID()}@example.com"
        // 하루 전에 실패만 있던 키
        (1..2).forEach { throttle.recordFailure("198.51.100.$it", staleEmail) }
        // 하루 전에 1시간 차단이 걸린 키: 차단 끝이 하루가 안 지났다
        clock.advance(Duration.ofMinutes(30))
        (1..20).forEach { throttle.recordFailure("198.51.100.$it", blockedEmail) }
        val cleanup = LoginAttemptCleanupJob(repository, clock)

        clock.advance(Duration.ofDays(1).minusMinutes(1))
        throttle.recordFailure(ip, email)
        cleanup.deleteStaleAttempts()

        assertThat(rowExists(LoginThrottle.emailKey(staleEmail))).isFalse()
        assertThat(rowExists(LoginThrottle.ipEmailKey("198.51.100.1", staleEmail))).isFalse()
        assertThat(rowExists(LoginThrottle.emailKey(blockedEmail))).isTrue()
        assertThat(rowExists(LoginThrottle.ipEmailKey(ip, email))).isTrue()
    }

    private fun throttledSeconds(
        ip: String,
        email: String,
    ): Int? {
        val e = catchThrowableOfType(BusinessException::class.java) { throttle.checkNotBlocked(ip, email) }
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
        private const val ONE_HOUR = 60 * 60
    }
}
