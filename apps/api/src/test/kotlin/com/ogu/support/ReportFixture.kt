package com.ogu.support

import com.ogu.report.application.CollectedWeek
import com.ogu.report.application.WeekRange
import com.ogu.report.application.WeeklyStatsCollector
import com.ogu.report.application.WeeklyStatsSource
import org.awaitility.Awaitility.await
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 주간 리포트 테스트의 컨텍스트 설정(008 research R11). 시계를 테스트가 움직이고, 집계를 회원별로 실패하게 만들 수 있다.
 * 이 설정을 쓰는 테스트는 `ogu.report.scheduler-enabled=false`로 주기 작업을 끄고 직접 부른다.
 */
@TestConfiguration(proxyBeanMethods = false)
class ReportTestConfiguration {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS))

    @Bean
    fun reportFaults(): ReportFaults = ReportFaults()

    /** 집계 앞에 실패 주입을 끼운다. 그 밖의 동작은 실제 집계 그대로다. */
    @Bean
    @Primary
    fun faultyStatsCollector(
        collector: WeeklyStatsCollector,
        faults: ReportFaults,
    ): FaultyStatsCollector = FaultyStatsCollector(collector, faults)
}

/** 집계가 실패할 회원. */
class ReportFaults {
    val failing: MutableSet<Long> = ConcurrentHashMap.newKeySet()
}

class FaultyStatsCollector(
    private val delegate: WeeklyStatsCollector,
    private val faults: ReportFaults,
) : WeeklyStatsSource {
    override fun collect(
        memberId: Long,
        week: WeekRange,
    ): CollectedWeek? {
        check(memberId !in faults.failing) { "집계 실패 주입" }
        return delegate.collect(memberId, week)
    }
}

/**
 * 지난주의 글, 공감, 댓글, 몬스터를 때를 정해 바로 넣는다. 저장 이벤트가 나가지 않으므로 감정 분석과 임베딩이 돌지 않는다.
 * 테스트마다 [newWeek]로 아직 쓰지 않은 주를 받아, 다른 테스트의 글과 섞이지 않게 한다.
 */
class ReportFixture(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: MutableClock,
) {
    /**
     * 시계를 아직 쓰지 않은 주의 수요일 정오(한국 시간)로 옮기고, 그 앞 주(리포트의 대상)를 돌려준다. 주 사이를 세 주씩
     * 띄워 앞 테스트의 글이 "앞 주"로 잡히지 않게 한다.
     */
    fun newWeek(): WeekRange {
        val thisWeek = WeekRange(WeekRange.containing(clock.instant()).start.plusWeeks(WEEK_GAP))
        clock.moveTo(
            thisWeek.start
                .plusDays(2)
                .atTime(LocalTime.NOON)
                .atZone(WeekRange.SEOUL)
                .toInstant(),
        )
        return thisWeek.previous()
    }

    /** [emotion]이 null이면 분석이 끝나지 않은 글이다. */
    fun post(
        author: TestMember,
        at: Instant,
        emotion: String? = "ANXIETY",
        risk: String = "NONE",
    ): Long {
        val profile = jdbcTemplate.queryForMap("select job_role, career_year from member where id = ?", author.id)
        val ts = Timestamp.from(at)
        val postId =
            jdbcTemplate.queryForObject(
                """
                insert into posts (author_id, author_job_role, author_career_year, content, comment_tone, risk_level,
                                   created_at, updated_at)
                values (?, ?, ?, '리포트 테스트의 글', 'COMFORT_ME', ?, ?, ?)
                returning id
                """.trimIndent(),
                Long::class.java,
                author.id,
                profile["job_role"],
                profile["career_year"],
                risk,
                ts,
                ts,
            )!!
        analysis(postId, emotion, ts)
        return postId
    }

    private fun analysis(
        postId: Long,
        emotion: String?,
        ts: Timestamp,
    ) {
        jdbcTemplate.update(
            """
            insert into emotion_analysis (post_id, status, emotion, intensity, next_attempt_at, post_created_at,
                                          completed_at)
            values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            postId,
            if (emotion == null) "PENDING" else "ANALYZED",
            emotion,
            emotion?.let { "LOW" },
            // 다른 컨텍스트의 재시도 스케줄러가 집어 가지 않게 멀리 둔다
            Timestamp.from(Instant.parse("2999-01-01T00:00:00Z")),
            ts,
            emotion?.let { ts },
        )
    }

    fun defaulted(postId: Long) {
        jdbcTemplate.update(
            "update emotion_analysis set status = 'DEFAULTED', emotion = 'LETHARGY', intensity = 'LOW' " +
                "where post_id = ?",
            postId,
        )
    }

    fun delete(postId: Long) {
        jdbcTemplate.update("update posts set deleted_at = now() where id = ?", postId)
    }

    fun hide(postId: Long) {
        jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'OPERATOR' where id = ?", postId)
    }

    fun defeatedMonster(
        postId: Long,
        at: Instant,
    ) {
        jdbcTemplate.update(
            """
            insert into monsters (post_id, emotion, max_hp, hp, status, defeated_at, created_at)
            values (?, 'ANXIETY', 10, 0, 'DEFEATED', ?, ?)
            """.trimIndent(),
            postId,
            Timestamp.from(at),
            Timestamp.from(at),
        )
    }

    fun like(
        postId: Long,
        by: TestMember,
        at: Instant,
    ) {
        jdbcTemplate.update(
            "insert into post_likes (post_id, member_id, created_at) values (?, ?, ?)",
            postId,
            by.id,
            Timestamp.from(at),
        )
    }

    fun comment(
        postId: Long,
        by: TestMember,
        at: Instant,
        state: String = "",
    ) {
        val ts = Timestamp.from(at)
        jdbcTemplate.update(
            """
            insert into comments (post_id, author_id, content, created_at, updated_at, deleted_at, hidden_at,
                                  hidden_reason)
            values (?, ?, '리포트 테스트의 댓글', ?, ?, ?, ?, ?)
            """.trimIndent(),
            postId,
            by.id,
            ts,
            ts,
            ts.takeIf { state == "deleted" },
            ts.takeIf { state == "hidden" },
            "OPERATOR".takeIf { state == "hidden" },
        )
    }

    fun row(
        member: TestMember,
        week: WeekRange,
    ): Map<String, Any?>? =
        jdbcTemplate
            .queryForList(
                "select * from weekly_report where member_id = ? and week_start = ?",
                member.id,
                Date.valueOf(week.start),
            ).firstOrNull()

    fun reportId(
        member: TestMember,
        week: WeekRange,
    ): Long = row(member, week)!!["id"] as Long

    fun notificationCount(member: TestMember): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from notification where receiver_id = ? and type = 'WEEKLY_REPORT'",
            Int::class.java,
            member.id,
        )!!

    fun awaitNotification(member: TestMember) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { notificationCount(member) == 1 }
    }

    /** 발행 직후의 첫 편지 시도가 끝날 때까지 기다린다(비동기 리스너). */
    fun awaitLetterAttempts(
        member: TestMember,
        week: WeekRange,
        attempts: Int,
    ) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until {
            val row = row(member, week)
            row != null && (row["letter_attempts"] as Int) >= attempts &&
                (row["letter_status"] != "PENDING" || row["letter_last_error"] != null)
        }
    }

    fun awaitLetterStatus(
        member: TestMember,
        week: WeekRange,
        status: String,
    ) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { row(member, week)?.get("letter_status") == status }
    }

    private companion object {
        const val WEEK_GAP = 3L
        val AWAIT_LIMIT: Duration = Duration.ofSeconds(10)
        val POLL: Duration = Duration.ofMillis(50)
    }
}
