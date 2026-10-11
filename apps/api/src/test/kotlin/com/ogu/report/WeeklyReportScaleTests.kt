package com.ogu.report

import com.ogu.TestcontainersConfiguration
import com.ogu.report.application.WeekRange
import com.ogu.report.application.WeeklyReportJob
import com.ogu.support.FutureClockSchedulers
import com.ogu.support.MutableClock
import com.ogu.support.ReportFaults
import com.ogu.support.ReportFixture
import com.ogu.support.ReportTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration

/**
 * SC-001, SC-002: 대상 회원 1천 명 가운데 10%가 실패해도 나머지는 모두 받고, 실패한 회원은 다음 실행에서 받는다.
 * 몇 번을 돌려도 회원마다 리포트와 알림이 하나다. 회원과 글은 SQL로 바로 넣는다. 한 차례의 한도는 운영과 같은
 * 500이다(다른 리포트 테스트는 4로 줄여 쓴다).
 */
@SpringBootTest(
    properties = [
        FutureClockSchedulers.REPORT,
        FutureClockSchedulers.RAID,
        FutureClockSchedulers.EMOTION,
        FutureClockSchedulers.SAFETY,
        FutureClockSchedulers.RECOMMEND,
        FutureClockSchedulers.RECOMMEND_BACKFILL,
    ],
)
@Import(TestcontainersConfiguration::class, ReportTestConfiguration::class)
class WeeklyReportScaleTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var job: WeeklyReportJob

    @Autowired
    lateinit var faults: ReportFaults

    @AfterEach
    fun tearDown() {
        faults.failing.clear()
    }

    @Test
    fun `SC-001 SC-002 1천 명 가운데 10퍼센트가 실패해도 나머지는 받고 실패한 회원은 다음 실행에 받는다`() {
        val week = ReportFixture(jdbcTemplate, clock).newWeek()
        val members = insertMembersWithPost(week)
        val broken = members.filterIndexed { index, _ -> index % 10 == 0 }
        faults.failing += broken

        // 한 차례의 한도(500)가 있어 여러 번 돈다. 실패한 회원 말고는 더 만들 것이 없을 때까지 돌린다
        var first = 0
        do {
            val tick = job.tick()
            first += tick.published
        } while (tick.published > 0)

        assertThat(first).isEqualTo(MEMBERS - broken.size)
        assertThat(reportCount(week)).isEqualTo(MEMBERS - broken.size)
        assertThat(runCompleted(week)).isFalse()

        faults.failing.clear()
        var second = 0
        do {
            val tick = job.tick()
            second += tick.published
        } while (!tick.completed)
        // 끝난 주를 다시 돌려도 늘지 않는다
        repeat(3) { second += job.tick().published }

        assertThat(second).isEqualTo(broken.size)
        assertThat(reportCount(week)).isEqualTo(MEMBERS)
        assertThat(runCompleted(week)).isTrue()
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until {
            notificationCount(members) == MEMBERS
        }
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until {
            notificationCount(members) == MEMBERS
        }
        val perMember =
            jdbcTemplate.queryForObject(
                "select max(c) from (select count(*) c from notification where type = 'WEEKLY_REPORT' " +
                    "and receiver_id between ? and ? group by receiver_id) t",
                Int::class.java,
                members.first(),
                members.last(),
            )
        assertThat(perMember).isEqualTo(1)
    }

    /** 회원 [MEMBERS]명과 저마다 그 주의 글 하나. 번호가 이어진 회원 ID를 돌려준다. */
    private fun insertMembersWithPost(week: WeekRange): List<Long> {
        val tag = (System.nanoTime() % TAG_RANGE).toString().padStart(TAG_DIGITS, '0')
        val ids =
            jdbcTemplate.queryForList(
                """
                insert into member (auth_method, nickname, nickname_key, job_role, career_year, onboarded_at,
                                    created_at, updated_at)
                select 'KAKAO', 's' || ? || n, 's' || ? || n, 'DEVELOPMENT', 'YEAR_3', now(), now(), now()
                from generate_series(1000, 1000 + ? - 1) n
                returning id
                """.trimIndent(),
                Long::class.java,
                tag,
                tag,
                MEMBERS,
            )
        val at = Timestamp.from(week.from.plus(Duration.ofDays(2)))
        jdbcTemplate.update(
            """
            insert into posts (author_id, author_job_role, author_career_year, content, comment_tone, created_at,
                               updated_at)
            select id, 'DEVELOPMENT', 'YEAR_3', '규모 테스트의 글', 'COMFORT_ME', ?, ?
            from member where id between ? and ?
            """.trimIndent(),
            at,
            at,
            ids.min(),
            ids.max(),
        )
        return ids.sorted()
    }

    private fun reportCount(week: WeekRange): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from weekly_report where week_start = ?",
            Int::class.java,
            Date.valueOf(week.start),
        )!!

    private fun runCompleted(week: WeekRange): Boolean =
        jdbcTemplate.queryForObject(
            "select count(*) from weekly_report_run where week_start = ?",
            Int::class.java,
            Date.valueOf(week.start),
        )!! == 1

    private fun notificationCount(members: List<Long>): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from notification where type = 'WEEKLY_REPORT' and receiver_id between ? and ?",
            Int::class.java,
            members.first(),
            members.last(),
        )!!

    private companion object {
        const val MEMBERS = 1000
        const val TAG_RANGE = 100_000L
        const val TAG_DIGITS = 5
    }
}
