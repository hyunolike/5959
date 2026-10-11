package com.ogu.report

import com.ogu.TestcontainersConfiguration
import com.ogu.report.application.WeekRange
import com.ogu.report.application.WeeklyReportJob
import com.ogu.report.application.WeeklyReportPublisher
import com.ogu.support.FutureClockSchedulers
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.ReportFaults
import com.ogu.support.ReportFixture
import com.ogu.support.ReportTestConfiguration
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.CompletableFuture

/**
 * 주간 리포트의 집계, 발행, 조회, 리포트 만들기(008 US1, US3, US4). 이 컨텍스트의 시계를 테스트가 움직이고 주기 작업을 끈
 * 채 직접 부른다. 테스트마다 아직 쓰지 않은 주를 써서 다른 테스트의 글과 섞이지 않는다. 한 차례의 한도는 4로 줄여 두었다.
 */
@SpringBootTest(
    properties = [
        FutureClockSchedulers.REPORT,
        FutureClockSchedulers.RAID,
        FutureClockSchedulers.EMOTION,
        FutureClockSchedulers.SAFETY,
        FutureClockSchedulers.RECOMMEND,
        FutureClockSchedulers.RECOMMEND_BACKFILL,
        "ogu.report.max-per-tick=4",
    ],
)
@Import(TestcontainersConfiguration::class, ReportTestConfiguration::class)
class WeeklyReportTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var job: WeeklyReportJob

    @Autowired
    lateinit var publisher: WeeklyReportPublisher

    @Autowired
    lateinit var faults: ReportFaults

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var safety: SafetyFixture
    private lateinit var report: ReportFixture
    private lateinit var week: WeekRange

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        report = ReportFixture(jdbcTemplate, clock)
        week = report.newWeek()
    }

    @AfterEach
    fun tearDown() {
        faults.failing.clear()
    }

    private fun at(
        day: Long,
        hour: Int = 12,
    ) = week.start
        .plusDays(day)
        .atTime(LocalTime.of(hour, 0))
        .atZone(WeekRange.SEOUL)
        .toInstant()

    private fun reportOf(member: TestMember) = safety.data(member, "/api/v1/members/me/weekly-reports/${week.start}")

    private fun countsOf(data: JsonNode): Map<String, Int> =
        data.get("emotionCounts").values().associate { it.get("emotion").asString() to it.get("count").asInt() }

    @Test
    fun `US1-AC1 지난주에 글을 쓴 회원에게 리포트가 생기고 알림이 하나 온다`() {
        val author = members.onboarded()
        report.post(author, at(1))

        val tick = job.tick()

        assertThat(tick.published).isEqualTo(1)
        assertThat(tick.completed).isTrue()
        assertThat(report.row(author, week)).isNotNull()
        report.awaitNotification(author)
        val item = safety.data(author, "/api/v1/notifications").get("items").first()
        assertThat(item.get("type").asString()).isEqualTo("WEEKLY_REPORT")
        assertThat(item.get("reportWeekStart").asString()).isEqualTo(week.start.toString())
        assertThat(item.get("postId").isNull).isTrue()
    }

    @Test
    fun `US1-AC3 리포트에 글 수, 감정별 수, 가장 많은 감정, 처치 수, 받은 공감과 댓글 수가 있다`() {
        val author = members.onboarded()
        val other = members.onboarded()
        val another = members.onboarded()
        val first = report.post(author, at(0), "ANXIETY")
        report.post(author, at(2), "ANXIETY")
        report.post(author, at(4), "IRRITATION")
        // 앞 주에 쓴 글의 몬스터가 이번 주에 처치됐다
        val old = report.post(author, week.from.minus(Duration.ofDays(3)))
        report.defeatedMonster(old, at(3))
        report.like(first, other, at(1))
        report.like(first, another, at(2))
        report.like(old, other, at(5))
        report.comment(first, other, at(1))
        report.comment(old, another, at(6))

        job.tick()

        val data = reportOf(author)
        assertThat(data.get("weekStart").asString()).isEqualTo(week.start.toString())
        assertThat(data.get("weekEnd").asString()).isEqualTo(week.end.toString())
        assertThat(data.get("postCount").asInt()).isEqualTo(3)
        assertThat(data.get("topEmotion").asString()).isEqualTo("ANXIETY")
        val counts = countsOf(data)
        assertThat(counts).containsEntry("ANXIETY", 2).containsEntry("IRRITATION", 1).containsEntry("LETHARGY", 0)
        assertThat(counts).hasSize(5)
        assertThat(data.get("unanalyzedCount").asInt()).isZero()
        assertThat(data.get("defeatedCount").asInt()).isEqualTo(1)
        assertThat(data.get("receivedLikes").asInt()).isEqualTo(3)
        assertThat(data.get("receivedComments").asInt()).isEqualTo(2)
        assertThat(data.get("previous").isNull).isTrue()
    }

    @Test
    fun `US1-AC3 기간 밖의 공감과 댓글과 처치, 내가 남긴 것, 지우거나 숨겨진 댓글은 세지 않는다`() {
        val author = members.onboarded()
        val other = members.onboarded()
        val post = report.post(author, at(1))
        val older = report.post(author, week.from.minus(Duration.ofDays(2)))
        report.defeatedMonster(older, week.from.minusSeconds(1))
        report.like(post, author, at(2))
        report.like(older, other, week.until)
        report.comment(post, author, at(2))
        report.comment(post, other, at(2), state = "deleted")
        report.comment(post, other, at(2), state = "hidden")
        report.comment(post, other, week.from.minusSeconds(1))

        job.tick()

        val data = reportOf(author)
        assertThat(data.get("defeatedCount").asInt()).isZero()
        assertThat(data.get("receivedLikes").asInt()).isZero()
        assertThat(data.get("receivedComments").asInt()).isZero()
    }

    @Test
    fun `US1-AC4 지난주에 글을 쓰지 않은 회원은 리포트도 알림도 없다`() {
        val writer = members.onboarded()
        val silent = members.onboarded()
        report.post(writer, at(1))
        // 이번 주와 그 앞 주에만 쓴 회원도 대상이 아니다
        report.post(silent, week.until.plusSeconds(60))
        report.post(silent, week.from.minusSeconds(60))

        job.tick()

        report.awaitNotification(writer)
        assertThat(report.row(silent, week)).isNull()
        assertThat(report.notificationCount(silent)).isZero()
    }

    @Test
    fun `US1-AC5 일요일 밤의 글은 지난주에 들고 월요일 0시의 글은 들지 않는다`() {
        val author = members.onboarded()
        report.post(author, week.from, "ANXIETY")
        report.post(author, week.until.minusSeconds(1), "LETHARGY")
        report.post(author, week.until, "IRRITATION")
        report.post(author, week.from.minusSeconds(1), "LONELINESS")

        job.tick()

        val data = reportOf(author)
        assertThat(data.get("postCount").asInt()).isEqualTo(2)
        val counts = countsOf(data)
        assertThat(counts).containsEntry("ANXIETY", 1).containsEntry("LETHARGY", 1).containsEntry("IRRITATION", 0)
    }

    @Test
    fun `US1-AC6 지운 글은 세지 않고 숨겨진 내 글은 센다`() {
        val author = members.onboarded()
        report.post(author, at(1), "ANXIETY")
        report.delete(report.post(author, at(2), "IRRITATION"))
        report.hide(report.post(author, at(3), "LETHARGY"))

        job.tick()

        val data = reportOf(author)
        assertThat(data.get("postCount").asInt()).isEqualTo(2)
        val counts = countsOf(data)
        assertThat(counts).containsEntry("LETHARGY", 1).containsEntry("IRRITATION", 0)
    }

    @Test
    fun `US1-AC6 지난주 글을 모두 지운 회원은 대상이 아니다`() {
        val author = members.onboarded()
        report.delete(report.post(author, at(1)))

        val tick = job.tick()

        assertThat(tick.published).isZero()
        assertThat(report.row(author, week)).isNull()
    }

    @Test
    fun `US1-AC7 분석되지 않은 글은 글 수에 들고 감정별 수에는 들지 않는다`() {
        val author = members.onboarded()
        report.post(author, at(1), "LONELINESS")
        report.post(author, at(2), emotion = null)
        // 24시간 동안 분석하지 못해 기본값을 받은 글도 회원의 마음으로 세지 않는다
        report.defaulted(report.post(author, at(3), emotion = null))

        job.tick()

        val data = reportOf(author)
        assertThat(data.get("postCount").asInt()).isEqualTo(3)
        assertThat(data.get("unanalyzedCount").asInt()).isEqualTo(2)
        assertThat(data.get("topEmotion").asString()).isEqualTo("LONELINESS")
        assertThat(data.get("emotionCounts").values().sumOf { it.get("count").asInt() }).isEqualTo(1)
    }

    @Test
    fun `US1-AC7 분석이 하나도 끝나지 않았으면 가장 많은 감정이 없다`() {
        val author = members.onboarded()
        report.post(author, at(1), emotion = null)

        job.tick()

        val data = reportOf(author)
        assertThat(data.get("topEmotion").isNull).isTrue()
        assertThat(data.get("unanalyzedCount").asInt()).isEqualTo(1)
    }

    @Test
    fun `가장 많은 감정이 같으면 가장 최근에 쓴 글의 감정이다`() {
        val author = members.onboarded()
        report.post(author, at(1), "ANXIETY")
        report.post(author, at(3), "IRRITATION")
        report.post(author, at(2), "LETHARGY")

        job.tick()

        assertThat(reportOf(author).get("topEmotion").asString()).isEqualTo("IRRITATION")
    }

    @Test
    fun `US1-AC8 발행한 뒤에 글을 지워도 리포트의 수치는 그대로다`() {
        val author = members.onboarded()
        val first = report.post(author, at(1))
        val second = report.post(author, at(2))
        job.tick()

        report.delete(first)
        report.delete(second)

        assertThat(reportOf(author).get("postCount").asInt()).isEqualTo(2)
    }

    @Test
    fun `US1-AC9 로그인하지 않으면 401이고 내 리포트가 아니거나 없는 주는 404다`() {
        val author = members.onboarded()
        val other = members.onboarded()
        report.post(author, at(1))
        job.tick()
        val path = "/api/v1/members/me/weekly-reports"

        mockMvc.perform(get("$path/${week.start}")).andExpect(status().isUnauthorized)
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized)
        // 경로에 회원이 없다. 다른 회원이 같은 주를 열면 자기 리포트를 찾고, 없으니 404다
        val tuesday = "${week.start.plusDays(1)}"
        val earlier = "${week.start.minusWeeks(1)}"
        listOf("${week.start}", tuesday, earlier, "어제", "2026-13-40").forEach {
            val viewer = if (it == "${week.start}") other else author
            mockMvc
                .perform(get("$path/$it").bearer(viewer.accessToken))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.error.code").value("WEEKLY_REPORT_NOT_FOUND"))
        }
        mockMvc.perform(get("$path/${week.start}").bearer(author.accessToken)).andExpect(status().isOk)
    }

    @Test
    fun `US3-AC1 일부 회원의 처리가 실패해도 나머지 회원의 리포트와 알림은 나간다`() {
        val authors = (1..5).map { members.onboarded().also { member -> report.post(member, at(1)) } }
        val broken = authors.take(2)
        faults.failing += broken.map { it.id }

        val tick = job.tick()

        assertThat(tick.published).isEqualTo(3)
        assertThat(tick.failed).isEqualTo(2)
        assertThat(tick.completed).isFalse()
        authors.drop(2).forEach { report.awaitNotification(it) }
        broken.forEach {
            assertThat(report.row(it, week)).isNull()
            assertThat(report.notificationCount(it)).isZero()
        }
    }

    @Test
    fun `US3-AC2 실패한 회원은 다음 실행에 받고 이미 받은 회원은 다시 받지 않는다`() {
        val authors = (1..3).map { members.onboarded().also { member -> report.post(member, at(1)) } }
        faults.failing += authors.first().id
        job.tick()
        authors.drop(1).forEach { report.awaitNotification(it) }
        val publishedAt = report.row(authors.last(), week)!!["published_at"]

        faults.failing.clear()
        clock.advance(Duration.ofMinutes(1))
        val tick = job.tick()

        assertThat(tick.published).isEqualTo(1)
        assertThat(tick.completed).isTrue()
        authors.forEach { report.awaitNotification(it) }
        safety.awaitListenersIdle()
        authors.forEach { assertThat(report.notificationCount(it)).isEqualTo(1) }
        assertThat(report.row(authors.last(), week)!!["published_at"]).isEqualTo(publishedAt)
    }

    @Test
    fun `US3-AC3 여러 번, 함께 돌려도 회원마다 리포트와 알림은 하나다`() {
        val authors = (1..4).map { members.onboarded().also { member -> report.post(member, at(1)) } }

        val runs = (1..4).map { CompletableFuture.supplyAsync { job.tick() } }.map { it.join() }
        job.tick()
        // 끝났다는 기록이 없어져 처음부터 다시 훑어도 늘지 않는다
        jdbcTemplate.update("delete from weekly_report_run where week_start = ?", java.sql.Date.valueOf(week.start))
        val again = job.tick()

        assertThat(runs.sumOf { it.published }).isEqualTo(4)
        assertThat(again.published).isZero()
        assertThat(again.completed).isTrue()
        authors.forEach { report.awaitNotification(it) }
        safety.awaitListenersIdle()
        authors.forEach { assertThat(report.notificationCount(it)).isEqualTo(1) }
        val reports =
            jdbcTemplate.queryForObject(
                "select count(*) from weekly_report where week_start = ?",
                Int::class.java,
                java.sql.Date.valueOf(week.start),
            )
        assertThat(reports).isEqualTo(4)
    }

    @Test
    fun `US3-AC4 중간에 멈췄다 다시 돌면 남은 회원부터 이어서 한다`() {
        val authors = (1..6).map { members.onboarded().also { member -> report.post(member, at(1)) } }

        // 한 차례의 한도(4)에서 멈춘 것은 도중에 내려간 것과 같다. 끝났다고 적지 않는다
        val first = job.tick()
        val second = job.tick()
        val third = job.tick()

        assertThat(first.published).isEqualTo(4)
        assertThat(first.completed).isFalse()
        assertThat(second.published).isEqualTo(2)
        assertThat(second.completed).isTrue()
        assertThat(third.published).isZero()
        authors.forEach { report.awaitNotification(it) }
    }

    @Test
    fun `US3-AC5 그 주 안에 늦게 돌아도 지난주 리포트를 만들고 한 주가 넘은 것은 만들지 않는다`() {
        val late = members.onboarded()
        val tooOld = members.onboarded()
        report.post(late, at(1))
        report.post(tooOld, week.from.minus(Duration.ofDays(3)))
        // 월요일 새벽에 내려가 있다가 일요일 밤에야 떴다
        clock.moveTo(week.until.plus(Duration.ofDays(6)).plus(Duration.ofHours(23)))

        val tick = job.tick()

        assertThat(tick.published).isEqualTo(1)
        assertThat(report.row(late, week)).isNotNull()
        assertThat(report.row(tooOld, week.previous())).isNull()
    }

    @Test
    fun `월요일 발행 시각 전에는 만들지 않는다`() {
        val author = members.onboarded()
        // 다음 주의 월요일 새벽으로 간다. 대상은 지금 주가 된다
        val target = WeekRange(week.start.plusWeeks(1))
        report.post(author, target.from.plusSeconds(60))
        val monday = target.until
        clock.moveTo(monday.plus(Duration.ofHours(4)).plus(Duration.ofMinutes(59)))

        val before = job.tick()
        clock.moveTo(monday.plus(Duration.ofHours(5)))
        val after = job.tick()

        assertThat(before.published).isZero()
        assertThat(before.completed).isFalse()
        assertThat(after.published).isEqualTo(1)
        assertThat(report.row(author, target)).isNotNull()
    }

    @Test
    fun `한 주를 끝낸 뒤에는 다시 훑지 않는다`() {
        val author = members.onboarded()
        report.post(author, at(1))
        assertThat(job.tick().completed).isTrue()
        val latecomer = members.onboarded()
        report.post(latecomer, at(2))

        val tick = job.tick()

        assertThat(tick.published).isZero()
        assertThat(report.row(latecomer, week)).isNull()
    }

    @Test
    fun `US4-AC1 리포트 목록은 최신 주부터이고 커서로 이어 받는다`() {
        val author = members.onboarded()
        val weeks = (0L..2L).map { WeekRange(week.start.minusWeeks(it)) }
        weeks.forEach { target ->
            report.post(author, target.from.plusSeconds(3600), "LETHARGY")
            publisher.publish(author.id, target)
        }
        val path = "/api/v1/members/me/weekly-reports"

        val first = safety.data(author, path, "size" to "2")
        val second = safety.data(author, path, "size" to "2", "cursor" to first.get("nextCursor").asString())

        assertThat(first.get("items").values().map { it.get("weekStart").asString() })
            .containsExactly(weeks[0].start.toString(), weeks[1].start.toString())
        val item = first.get("items").first()
        assertThat(item.get("weekEnd").asString()).isEqualTo(weeks[0].end.toString())
        assertThat(item.get("postCount").asInt()).isEqualTo(1)
        assertThat(item.get("topEmotion").asString()).isEqualTo("LETHARGY")
        assertThat(second.get("items").values().map { it.get("weekStart").asString() })
            .containsExactly(weeks[2].start.toString())
        assertThat(second.get("nextCursor").isNull).isTrue()
        mockMvc
            .perform(get(path).param("cursor", "어제").bearer(author.accessToken))
            .andExpect(status().isBadRequest)
        mockMvc.perform(get(path).param("size", "51").bearer(author.accessToken)).andExpect(status().isBadRequest)
    }

    @Test
    fun `US4-AC3 받은 리포트가 없으면 빈 목록이다`() {
        val data = safety.data(members.onboarded(), "/api/v1/members/me/weekly-reports")

        assertThat(data.get("items").isEmpty).isTrue()
        assertThat(data.get("nextCursor").isNull).isTrue()
    }

    @Test
    fun `US4-AC4 바로 앞 주의 리포트가 있으면 견준 값이 있고 없으면 null이다`() {
        val author = members.onboarded()
        val before = week.previous()
        report.post(author, before.from.plusSeconds(3600), "LETHARGY")
        report.post(author, before.from.plusSeconds(7200), "LETHARGY")
        publisher.publish(author.id, before)
        report.post(author, at(1), "ANXIETY")
        job.tick()

        val current = reportOf(author)
        val earlier = safety.data(author, "/api/v1/members/me/weekly-reports/${before.start}")

        assertThat(current.get("previous").get("postCount").asInt()).isEqualTo(2)
        assertThat(current.get("previous").get("topEmotion").asString()).isEqualTo("LETHARGY")
        assertThat(earlier.get("previous").isNull).isTrue()
    }
}
