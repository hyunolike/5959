package com.ogu.support

import org.awaitility.Awaitility.await
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Date
import java.sql.Timestamp
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

    /** 글쓰기도 켠다(012). 주기 작업은 끄고 테스트가 실행기를 직접 부른다. 켜 두면 조용한 시간마다 글이 올라온다. */
    const val POSTING = "ogu.companion.posting.enabled=true"
    const val NO_POSTING_SCHEDULER = "ogu.companion.posting.scheduler-enabled=false"

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

    /** 회원이 받은 알림의 수. AI 회원은 언제나 0이어야 한다(009 FR-017). */
    fun notificationCount(memberId: Long): Int {
        val sql = "select count(*) from notification where receiver_id = ?"
        return jdbcTemplate.queryForObject(sql, Int::class.java, memberId)!!
    }

    /** 이 글에 AI 친구가 남긴 반응의 기록(남긴 순). 친구 한 명에 한 행이다(012). */
    fun entries(postId: Long): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("select * from companion_reaction_entry where post_id = ? order by id", postId)

    /** 이 글의 다음 차례. 닫힌 기록이면 null이다. */
    fun nextAttemptAt(postId: Long): Instant? = (reaction(postId)!!["next_attempt_at"] as Timestamp?)?.toInstant()

    /** 친구(1, 2, 3)를 가장 오래 쉰 순서로 놓은 것. 실행기가 고를 차례와 같다. */
    fun restOrder(): List<Int> {
        val sql = "select companion_id, max(id) as last_id from companion_reaction_entry group by companion_id"
        val last = jdbcTemplate.query(sql) { rs, _ -> rs.getInt("companion_id") to rs.getLong("last_id") }.toMap()
        return memberIdByCompanion.keys.sortedWith(compareBy<Int> { last[it] ?: Long.MIN_VALUE }.thenBy { it })
    }

    fun moveTo(instant: Instant) {
        checkNotNull(clock) { "시계를 움직이는 컨텍스트가 아닙니다" }.moveTo(instant)
    }

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

    /**
     * 시계를 아직 아무도 글을 쓰지 않은 날의 [hour]시(한국 시간)로 옮긴다(012 research R20). 컨텍스트들이 DB를 함께 쓰고
     * 시계를 앞으로 움직이는 테스트가 있어, "한동안 새 글이 없었다"를 만들려면 가장 새 글보다 뒤로 가야 한다. 그날은
     * AI 친구의 글 기록도 없다.
     */
    fun quietDay(hour: Int = 8) {
        val mutable = checkNotNull(clock) { "시계를 움직이는 컨텍스트가 아닙니다" }
        val newest = jdbcTemplate.queryForObject("select max(created_at) from posts", Timestamp::class.java)
        val latestPost = newest?.toInstant()
        val latest = listOfNotNull(latestPost, mutable.instant()).max()
        mutable.moveTo(
            latest
                .atZone(SEOUL)
                .toLocalDate()
                .plusDays(2)
                .atTime(hour, 0)
                .atZone(SEOUL)
                .toInstant(),
        )
    }

    /** AI 친구의 글 기록(만든 순). [since]부터 만든 것만 본다. */
    fun postAttempts(since: Instant): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            "select * from companion_post where created_at >= ? order by id",
            Timestamp.from(since),
        )

    /** AI 친구가 [since]부터 쓴 글(쓴 순). */
    fun aiPosts(since: Instant): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            "select * from posts where author_kind = 'AI' and created_at >= ? order by id",
            Timestamp.from(since),
        )

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

        /** 뒤따르는 친구의 차례가 반드시 오는 시간(`follow-up.max-gap`). */
        val FOLLOW_UP: Duration = Duration.ofMinutes(10)
        val AWAIT_LIMIT: Duration = Duration.ofSeconds(10)
        val POLL: Duration = Duration.ofMillis(50)

        /** [CompanionSettings.NO_LIMIT]로 띄운 컨텍스트의 하루 상한. */
        const val LIMIT = 100_000
    }
}
