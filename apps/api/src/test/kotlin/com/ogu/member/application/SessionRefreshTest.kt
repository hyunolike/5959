package com.ogu.member.application

import com.ogu.TestcontainersConfiguration
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * T058: 세션 갱신(US4-AC1~AC3, research R2). data-model.md `auth_session`의 refresh 흐름도의 모든 분기를 실제 DB와
 * 주입한 시계로 확인한다. `SELECT ... FOR UPDATE` 직렬화는 DB가 있어야 검증할 수 있어서 통합 테스트로 둔다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, SessionRefreshTest.ClockOverride::class)
class SessionRefreshTest {
    @Autowired
    lateinit var sessionService: SessionService

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val start = Instant.parse("2026-09-28T00:00:00Z")

    @BeforeEach
    fun resetClock() {
        clock.set(start)
    }

    @Test
    fun `정상 교체하면 새 refresh 토큰을 주고 직전 해시와 교체 시각을 남기며 만료를 14일 뒤로 다시 잡는다`() {
        val issued = issue(onboarded = true)
        clock.advance(Duration.ofDays(1))

        val result = sessionService.refresh(requireNotNull(issued.refreshToken))

        val newToken = requireNotNull(result.tokens.refreshToken)
        assertThat(newToken).isNotEqualTo(issued.refreshToken).matches("^[A-Za-z0-9_-]{43}$")
        assertThat(result.tokens.sessionId).isEqualTo(issued.sessionId)
        assertThat(result.tokens.refreshTokenExpiresAt).isEqualTo(clock.instant().plus(Duration.ofDays(14)))
        val row = sessionRow(issued.sessionId)
        assertThat(row["refresh_token_hash"]).isEqualTo(sha256Hex(newToken))
        assertThat(row["previous_refresh_token_hash"]).isEqualTo(sha256Hex(issued.refreshToken!!))
        assertThat(instant(row["rotated_at"])).isEqualTo(clock.instant())
        assertThat(instant(row["expires_at"])).isEqualTo(clock.instant().plus(Duration.ofDays(14)))
        assertThat(row["revoked_at"]).isNull()
        val jwt = jwtDecoder.decode(result.tokens.accessToken)
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(issued.sessionId.toString())
        assertThat(jwt.subject).isEqualTo(result.member.id.toString())
    }

    @Test
    fun `교체한 만료 시각은 최대 만료를 넘지 않는다`() {
        val issued = issue(onboarded = true)
        clock.advance(Duration.ofDays(10))
        val second = sessionService.refresh(issued.refreshToken!!)
        clock.advance(Duration.ofDays(10))

        val third = sessionService.refresh(second.tokens.refreshToken!!)

        val absolute = start.plus(Duration.ofDays(30))
        assertThat(third.tokens.refreshTokenExpiresAt).isEqualTo(absolute)
        assertThat(instant(sessionRow(issued.sessionId)["expires_at"])).isEqualTo(absolute)
    }

    @Test
    fun `access 토큰의 온보딩 여부는 갱신 시점의 회원 상태를 따른다`() {
        val issued = issue(onboarded = false)
        completeOnboarding(memberOf(issued.sessionId))

        val result = sessionService.refresh(issued.refreshToken!!)

        assertThat(result.member.isOnboarded).isTrue()
        assertThat(jwtDecoder.decode(result.tokens.accessToken).getClaim<Boolean>("onboarded")).isTrue()
    }

    @Test
    fun `US4-AC2 마지막 활동 뒤 14일이 지나면 SESSION_EXPIRED`() {
        val issued = issue(onboarded = true)
        clock.advance(Duration.ofDays(5))
        val refreshed = sessionService.refresh(issued.refreshToken!!)
        clock.advance(Duration.ofDays(14))

        assertSessionExpired { sessionService.refresh(refreshed.tokens.refreshToken!!) }
        assertThat(sessionRow(issued.sessionId)["revoked_at"]).isNull()
    }

    @Test
    fun `US4-AC3 로그인 30일 뒤에는 활동 중이어도 SESSION_EXPIRED`() {
        val issued = issue(onboarded = true)
        var token = issued.refreshToken!!
        listOf(10L, 10L, 9L).forEach { days ->
            clock.advance(Duration.ofDays(days))
            token = sessionService.refresh(token).tokens.refreshToken!!
        }
        clock.advance(Duration.ofDays(1))

        assertSessionExpired { sessionService.refresh(token) }
    }

    @Test
    fun `교체 후 30초 안에 직전 토큰이 오면 access만 발급하고 refresh 토큰은 null이며 교체하지 않는다`() {
        val issued = issue(onboarded = true)
        val rotated = sessionService.refresh(issued.refreshToken!!)
        clock.advance(Duration.ofSeconds(30))

        val grace = sessionService.refresh(issued.refreshToken!!)

        assertThat(grace.tokens.refreshToken).isNull()
        assertThat(grace.tokens.sessionId).isEqualTo(issued.sessionId)
        assertThat(grace.tokens.refreshTokenExpiresAt).isEqualTo(rotated.tokens.refreshTokenExpiresAt)
        assertThat(jwtDecoder.decode(grace.tokens.accessToken).getClaimAsString("sid"))
            .isEqualTo(issued.sessionId.toString())
        val row = sessionRow(issued.sessionId)
        assertThat(row["refresh_token_hash"]).isEqualTo(sha256Hex(rotated.tokens.refreshToken!!))
        assertThat(row["previous_refresh_token_hash"]).isEqualTo(sha256Hex(issued.refreshToken!!))
        assertThat(instant(row["rotated_at"])).isEqualTo(start)
        assertThat(row["revoked_at"]).isNull()
    }

    @Test
    fun `교체 후 30초가 지나 직전 토큰이 오면 세션을 REUSE_DETECTED로 무효화하고 새 토큰도 쓸 수 없다`() {
        val issued = issue(onboarded = true)
        val rotated = sessionService.refresh(issued.refreshToken!!)
        clock.advance(Duration.ofSeconds(31))

        assertSessionExpired { sessionService.refresh(issued.refreshToken!!) }

        val row = sessionRow(issued.sessionId)
        assertThat(instant(row["revoked_at"])).isEqualTo(clock.instant())
        assertThat(row["revoke_reason"]).isEqualTo("REUSE_DETECTED")
        assertThat(sessionService.isActive(issued.sessionId, memberOf(issued.sessionId))).isFalse()
        assertSessionExpired { sessionService.refresh(rotated.tokens.refreshToken!!) }
    }

    @Test
    fun `무효화된 세션의 직전 토큰은 유예 구간 안이어도 SESSION_EXPIRED`() {
        val issued = issue(onboarded = true)
        sessionService.refresh(issued.refreshToken!!)
        jdbcTemplate.update(
            "update auth_session set revoked_at = ?, revoke_reason = 'LOGOUT' where id = ?",
            Timestamp.from(start),
            issued.sessionId,
        )
        clock.advance(Duration.ofSeconds(5))

        assertSessionExpired { sessionService.refresh(issued.refreshToken!!) }
        assertThat(sessionRow(issued.sessionId)["revoke_reason"]).isEqualTo("LOGOUT")
    }

    @Test
    fun `로그아웃한 세션의 현재 토큰은 SESSION_EXPIRED이고 무효화 기록은 바뀌지 않는다`() {
        val issued = issue(onboarded = true)
        jdbcTemplate.update(
            "update auth_session set revoked_at = ?, revoke_reason = 'LOGOUT' where id = ?",
            Timestamp.from(start),
            issued.sessionId,
        )

        assertSessionExpired { sessionService.refresh(issued.refreshToken!!) }
        assertThat(sessionRow(issued.sessionId)["revoke_reason"]).isEqualTo("LOGOUT")
    }

    @Test
    fun `모르는 토큰과 빈 토큰은 SESSION_EXPIRED`() {
        assertSessionExpired { sessionService.refresh("unknown-token-${UUID.randomUUID()}") }
        assertSessionExpired { sessionService.refresh("") }
    }

    @Test
    fun `같은 토큰으로 동시 요청 두 건이 모두 로그아웃 없이 끝난다`() {
        val issued = issue(onboarded = true)
        val token = issued.refreshToken!!
        val executor = Executors.newFixedThreadPool(2)
        val results: List<Future<RefreshResult>>
        try {
            // 테스트가 먼저 세션 행을 잠그고 두 요청을 보낸다. 두 요청이 모두 같은 행의 잠금을 기다리는 것을 확인한 뒤
            // 잠금을 풀어, 두 요청이 반드시 겹치게 만든다(순서와 관계없이 결과가 같아야 한다).
            results =
                TransactionTemplate(transactionManager).execute {
                    jdbcTemplate.queryForList(
                        "select id from auth_session where id = ? for update",
                        issued.sessionId,
                    )
                    val futures = (1..2).map { executor.submit(Callable { sessionService.refresh(token) }) }
                    awaitLockWaiters(expected = 2)
                    futures
                }!!
            val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }

            val rotated = outcomes.filter { it.tokens.refreshToken != null }
            val grace = outcomes.filter { it.tokens.refreshToken == null }
            assertThat(rotated).hasSize(1)
            assertThat(grace).hasSize(1)
            outcomes.forEach {
                assertThat(jwtDecoder.decode(it.tokens.accessToken).getClaimAsString("sid"))
                    .isEqualTo(issued.sessionId.toString())
            }
            val row = sessionRow(issued.sessionId)
            assertThat(row["refresh_token_hash"]).isEqualTo(sha256Hex(rotated.single().tokens.refreshToken!!))
            assertThat(row["previous_refresh_token_hash"]).isEqualTo(sha256Hex(token))
            assertThat(row["revoked_at"]).isNull()
            assertThat(row["revoke_reason"]).isNull()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitLockWaiters(expected: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbcTemplate.queryForObject(
                    """
                    select count(*) from pg_stat_activity
                    where datname = current_database() and wait_event_type = 'Lock' and query ilike '%auth_session%'
                    """.trimIndent(),
                    Int::class.java,
                )
            if (waiting == expected) return
            Thread.sleep(LOCK_POLL_MILLIS)
        }
        error("refresh 요청 ${expected}건이 세션 행 잠금을 기다리지 않았다")
    }

    private fun assertSessionExpired(action: () -> Unit) {
        assertThatThrownBy(action)
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_EXPIRED)
    }

    private fun issue(onboarded: Boolean): IssuedTokens {
        val memberId = insertMember()
        if (onboarded) completeOnboarding(memberId)
        return sessionService.issue(memberId = memberId, onboarded = onboarded)
    }

    private fun completeOnboarding(memberId: Long) {
        jdbcTemplate.update(
            """
            update member set nickname = ?, nickname_key = ?, job_role = 'DEVELOPMENT', career_year = 'YEAR_1',
              onboarded_at = now() where id = ?
            """.trimIndent(),
            "n$memberId",
            "n$memberId",
            memberId,
        )
    }

    private fun insertMember(): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "insert into member (auth_method, created_at, updated_at) values ('KAKAO', now(), now()) returning id",
                Long::class.java,
            ),
        )

    private fun memberOf(sessionId: UUID): Long =
        requireNotNull(
            jdbcTemplate.queryForObject("select member_id from auth_session where id = ?", Long::class.java, sessionId),
        )

    private fun sessionRow(sessionId: UUID): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            """
            select refresh_token_hash, previous_refresh_token_hash, rotated_at, expires_at, revoked_at, revoke_reason
            from auth_session where id = ?
            """.trimIndent(),
            sessionId,
        )

    private fun instant(value: Any?): Instant? =
        when (value) {
            null -> null
            is Timestamp -> value.toInstant()
            is java.time.OffsetDateTime -> value.toInstant()
            else -> error("시각 값이 아니다: $value")
        }

    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    /** 테스트가 시각을 옮길 수 있는 시계. 이 테스트의 컨텍스트에서만 기본 [Clock]을 대신한다. */
    class MutableClock(
        @Volatile private var now: Instant,
    ) : Clock() {
        fun set(instant: Instant) {
            now = instant
        }

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ClockOverride {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(Instant.parse("2026-09-28T00:00:00Z"))
    }

    companion object {
        private const val LOCK_POLL_MILLIS = 20L
    }
}
