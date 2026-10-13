package com.ogu.support

import org.awaitility.Awaitility.await
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Date
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** AI 친구 테스트가 켜고 끄는 설정(009 research R14). */
object CompanionSettings {
    const val ENABLED = "ogu.companion.enabled=true"

    /** 주기 작업을 끄고 테스트가 실행기를 직접 부른다. 켜 두면 다른 테스트의 글까지 맡는다. */
    const val NO_SCHEDULER = "ogu.companion.scheduler-enabled=false"

    /** 컨텍스트들이 DB를 함께 써서 하루의 몫도 함께 쓴다. 몫을 보지 않는 테스트는 넉넉히 둔다. */
    const val NO_LIMIT = "ogu.companion.daily-limit=100000"

    /** 시계를 하루 넘게 움직여도 테스트 회원의 access 토큰(기본 15분)이 만료되지 않게 한다. */
    const val LONG_TOKEN = "ogu.auth.jwt.access-token-ttl=10d"
}

/** AI 친구 테스트의 컨텍스트 설정. 시계를 테스트가 움직인다. */
@TestConfiguration(proxyBeanMethods = false)
class CompanionTestConfiguration {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS))
}

/** AI 친구의 반응 기록과 남긴 댓글, 공감을 읽기 전용 SQL로 확인하는 테스트 도우미. */
class CompanionFixture(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: MutableClock? = null,
) {
    /** AI 친구의 회원 번호(친구 번호 순). */
    val memberIds: List<Long> by lazy {
        jdbcTemplate.queryForList("select member_id from companion order by id", Long::class.java)
    }

    /** 친구 번호(1, 2, 3)별 회원 번호. */
    val memberIdByCompanion: Map<Int, Long> by lazy {
        jdbcTemplate
            .query("select id, member_id from companion") { rs, _ -> rs.getInt("id") to rs.getLong("member_id") }
            .toMap()
    }

    fun reaction(postId: Long): Map<String, Any?>? =
        jdbcTemplate.queryForList("select * from companion_reaction where post_id = ?", postId).firstOrNull()

    /** 글이 커밋된 뒤 비동기로 생기는 반응 기록을 기다린다. */
    fun awaitEnrolled(postId: Long) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { reaction(postId) != null }
    }

    /** 반응 기록이 생기고 글의 위험 분류(첫 시도)가 끝날 때까지 기다린다. */
    fun awaitReady(postId: Long) {
        awaitEnrolled(postId)
        awaitRisk(postId) { it != "PENDING" }
    }

    /** 글의 가장 새 위험 판정의 상태가 [until]을 채울 때까지 기다린다. */
    fun awaitRisk(
        postId: Long,
        until: (status: String) -> Boolean,
    ) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until {
            jdbcTemplate
                .queryForList(
                    "select status from risk_assessment where target_type = 'POST' and target_id = ? " +
                        "order by id desc limit 1",
                    String::class.java,
                    postId,
                ).firstOrNull()
                ?.let(until) ?: false
        }
    }

    /** AI 친구가 이 글에 남긴 지우지 않은 댓글(오래된 순). */
    fun comments(postId: Long): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            """
            select c.id, c.author_id, c.content, c.hidden_at is not null as hidden from comments c
            where c.post_id = ? and c.deleted_at is null
              and c.author_id in (select member_id from companion)
            order by c.id
            """.trimIndent(),
            postId,
        )

    /** AI 친구가 이 글에 누른 공감의 회원 번호. */
    fun likers(postId: Long): List<Long> =
        jdbcTemplate.queryForList(
            "select member_id from post_likes where post_id = ? and member_id in (select member_id from companion)",
            Long::class.java,
            postId,
        )

    fun pass(duration: Duration) {
        checkNotNull(clock) { "시계를 움직이는 컨텍스트가 아닙니다" }.advance(duration)
    }

    /**
     * 시계를 아직 아무도 쓰지 않은 날의 정오(한국 시간)로 옮긴다. 시계는 앞으로만 가므로 모레는 아무도 쓰지 않은 날이다.
     * 하루의 몫을 재는 테스트가 앞뒤 테스트의 몫과 섞이지 않게 한다.
     */
    fun freshDay() {
        val mutable = checkNotNull(clock) { "시계를 움직이는 컨텍스트가 아닙니다" }
        mutable.moveTo(
            today()
                .plusDays(2)
                .atTime(LocalTime.NOON)
                .atZone(SEOUL)
                .toInstant(),
        )
    }

    /** 지금 날(한국 시간)에 쓴 몫. 기록이 없으면 0이다. */
    fun usedToday(): Int {
        val sql = "select used from companion_daily_usage where day = ?"
        return jdbcTemplate.queryForList(sql, Int::class.java, Date.valueOf(today())).firstOrNull() ?: 0
    }

    /** 오늘의 몫을 이미 [used]번 쓴 것으로 만든다(상한을 낮춰 띄우지 않고 "몫이 얼마 남지 않은 날"을 만든다). */
    fun spendToday(used: Int) {
        jdbcTemplate.update(
            "insert into companion_daily_usage (day, used) values (?, ?) " +
                "on conflict (day) do update set used = excluded.used",
            Date.valueOf(today()),
            used,
        )
    }

    private fun today(): LocalDate = checkNotNull(clock).instant().atZone(SEOUL).toLocalDate()

    /** 반응 기록을 [age]만큼 전에 올라온 글의 것으로 바꾼다(시계를 움직이지 않는 컨텍스트용). */
    fun backdate(
        postId: Long,
        age: Duration,
    ) {
        jdbcTemplate.update(
            "update companion_reaction set post_created_at = post_created_at - make_interval(secs => ?), " +
                "next_attempt_at = next_attempt_at - make_interval(secs => ?) where post_id = ?",
            age.seconds,
            age.seconds,
            postId,
        )
    }

    companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val WAIT: Duration = Duration.ofMinutes(10)
        val AWAIT_LIMIT: Duration = Duration.ofSeconds(10)
        val POLL: Duration = Duration.ofMillis(50)

        /** [CompanionSettings.NO_LIMIT]로 띄운 컨텍스트의 하루 상한. */
        const val LIMIT = 100_000
    }
}
