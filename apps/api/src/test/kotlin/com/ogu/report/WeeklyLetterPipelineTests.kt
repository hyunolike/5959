package com.ogu.report

import com.ogu.TestcontainersConfiguration
import com.ogu.ai.ClassifiedEmotion
import com.ogu.report.application.WeekRange
import com.ogu.report.application.WeeklyLetterRunner
import com.ogu.report.application.WeeklyReportJob
import com.ogu.report.application.WeeklyReportPublisher
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.ReportFixture
import com.ogu.support.ReportTestConfiguration
import com.ogu.support.SafetyFixture
import com.ogu.support.ScriptedWeeklyLetterWriter
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.LocalTime

/**
 * 주간 리포트의 편지(008 US2, research R6~R8). 가짜 편지 쓰기는 수치로 결과가 정해진다: 받은 댓글 13이면 계속 실패,
 * 쓴 글 7이면 두 번 실패한 뒤 성공. 시계를 테스트가 움직이고 재시도는 실행기를 직접 부른다.
 */
@SpringBootTest(properties = ["ogu.report.scheduler-enabled=false", "ogu.report.max-per-tick=4"])
@Import(TestcontainersConfiguration::class, ReportTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class WeeklyLetterPipelineTests {
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
    lateinit var runner: WeeklyLetterRunner

    @Autowired
    lateinit var writer: ScriptedWeeklyLetterWriter

    private lateinit var members: MemberFixture
    private lateinit var safety: SafetyFixture
    private lateinit var report: ReportFixture
    private lateinit var week: WeekRange

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        report = ReportFixture(jdbcTemplate, clock)
        week = report.newWeek()
    }

    private fun at(day: Long) =
        week.start
            .plusDays(day)
            .atTime(LocalTime.NOON)
            .atZone(WeekRange.SEOUL)
            .toInstant()

    private fun reportOf(member: TestMember) = safety.data(member, "/api/v1/members/me/weekly-reports/${week.start}")

    /** 글 [posts]개와 받은 댓글 [comments]개가 있는 지난주를 만든다. */
    private fun authorWith(
        posts: Int,
        comments: Int = 0,
        emotion: String = "ANXIETY",
    ): TestMember {
        val author = members.onboarded()
        val ids = (0 until posts).map { report.post(author, at(1).plusSeconds(it.toLong()), emotion) }
        if (comments > 0) {
            val commenter = members.onboarded()
            repeat(comments) { report.comment(ids.first(), commenter, at(2).plusSeconds(it.toLong())) }
        }
        return author
    }

    @Test
    fun `US2-AC1 리포트에 수치로 쓴 편지가 보인다`() {
        val author = authorWith(posts = 2, comments = 3)

        job.tick()
        report.awaitLetterStatus(author, week, "DONE")

        val data = reportOf(author)
        assertThat(data.get("letterStatus").asString()).isEqualTo("DONE")
        assertThat(data.get("letter").asString()).isEqualTo("지난주에 글을 2개 쓰셨어요. 공감 0개와 댓글 3개가 곁에 있었어요.")
    }

    @Test
    fun `US2-AC5 편지 쓰기가 받는 것은 수치뿐이고 앞 주의 리포트가 있으면 그 글 수와 감정이 더해진다`() {
        val author = authorWith(posts = 2, comments = 1)
        val before = week.previous()
        report.post(author, before.from.plusSeconds(3600), "LETHARGY")
        publisher.publish(author.id, before)

        job.tick()
        report.awaitLetterStatus(author, week, "DONE")

        val input = writer.inputOf(report.reportId(author, week))!!
        assertThat(input.postCount).isEqualTo(2)
        assertThat(input.emotionCounts).containsEntry(ClassifiedEmotion.ANXIETY, 2).hasSize(5)
        assertThat(input.topEmotion).isEqualTo(ClassifiedEmotion.ANXIETY)
        assertThat(input.receivedComments).isEqualTo(1)
        assertThat(input.previousPostCount).isEqualTo(1)
        assertThat(input.previousTopEmotion).isEqualTo(ClassifiedEmotion.LETHARGY)
    }

    @Test
    fun `US2-AC2 AI가 실패해도 리포트와 알림은 나가고 편지는 PENDING이다`() {
        val author = authorWith(posts = 1, comments = 13)

        job.tick()
        report.awaitLetterAttempts(author, week, 1)

        report.awaitNotification(author)
        val data = reportOf(author)
        assertThat(data.get("letterStatus").asString()).isEqualTo("PENDING")
        assertThat(data.get("letter").isNull).isTrue()
        assertThat(data.get("receivedComments").asInt()).isEqualTo(13)
        assertThat(report.row(author, week)).containsEntry("letter_last_error", "UPSTREAM_ERROR")
    }

    @Test
    fun `US2-AC3 다시 시도해 써지면 DONE이 되고 알림은 다시 가지 않는다`() {
        val author = authorWith(posts = 7)
        job.tick()
        report.awaitLetterAttempts(author, week, 1)
        report.awaitNotification(author)

        // 아직 차례가 아니면 맡지 않는다
        clock.advance(Duration.ofSeconds(29))
        runner.attempt(author.id, week.start)
        assertThat(report.row(author, week)).containsEntry("letter_attempts", 1)

        clock.advance(Duration.ofSeconds(1))
        runner.attempt(author.id, week.start)
        val second = report.row(author, week)
        assertThat(second).containsEntry("letter_attempts", 2).containsEntry("letter_status", "PENDING")

        clock.advance(Duration.ofSeconds(60))
        runner.runDue()

        val row = report.row(author, week)!!
        assertThat(row).containsEntry("letter_status", "DONE").containsEntry("letter_attempts", 3)
        assertThat(row).containsEntry("letter_last_error", null).containsEntry("letter_next_attempt_at", null)
        assertThat(reportOf(author).get("letter").asString()).startsWith("지난주에 글을 7개")
        safety.awaitListenersIdle()
        assertThat(report.notificationCount(author)).isEqualTo(1)
    }

    @Test
    fun `US2-AC4 24시간 동안 쓰지 못하면 GIVEN_UP이고 더 시도하지 않는다`() {
        val author = authorWith(posts = 1, comments = 13)
        job.tick()
        report.awaitLetterAttempts(author, week, 1)
        val reportId = report.reportId(author, week)

        clock.advance(Duration.ofHours(24))
        runner.runDue()
        val calls = writer.callsOf(reportId)
        clock.advance(Duration.ofHours(1))
        runner.runDue()

        assertThat(report.row(author, week)).containsEntry("letter_status", "GIVEN_UP").containsEntry("letter", null)
        assertThat(writer.callsOf(reportId)).isEqualTo(calls)
        // 시계를 하루 넘게 돌려 이 회원의 토큰이 끝났다. 응답은 저장된 상태를 그대로 옮기므로 행으로 확인한다
        assertThat(report.row(author, week)).containsEntry("letter_next_attempt_at", null)
    }

    @Test
    fun `US2-AC6 위기 글이 있던 주는 SUPPORT이고 AI를 부르지 않는다`() {
        val author = members.onboarded()
        report.post(author, at(1), "ANXIETY")
        report.post(author, at(2), "LETHARGY", risk = "CRISIS")
        // 우려 단계만 있는 회원은 AI 편지를 그대로 받는다
        val concerned = members.onboarded()
        report.post(concerned, at(1), "LETHARGY", risk = "CONCERN")

        job.tick()
        report.awaitLetterStatus(concerned, week, "DONE")
        safety.awaitListenersIdle()
        clock.advance(Duration.ofMinutes(10))
        runner.runDue()

        val row = report.row(author, week)!!
        assertThat(row).containsEntry("letter_status", "SUPPORT").containsEntry("letter", null)
        assertThat(row).containsEntry("letter_attempts", 0).containsEntry("letter_next_attempt_at", null)
        assertThat(writer.callsOf(row["id"] as Long)).isZero()
        val data = reportOf(author)
        assertThat(data.get("letterStatus").asString()).isEqualTo("SUPPORT")
        assertThat(data.get("postCount").asInt()).isEqualTo(2)
        report.awaitNotification(author)
    }

    @Test
    fun `US2-AC7 가려지는 낱말이 든 답은 편지로 쓰지 않고 다시 시도한다`() {
        writer.answers[5] = "병신 같은 한 주였어요."
        val author = authorWith(posts = 5)

        job.tick()
        report.awaitLetterAttempts(author, week, 1)

        assertThat(report.row(author, week))
            .containsEntry("letter_status", "PENDING")
            .containsEntry("letter", null)
            .containsEntry("letter_last_error", "INVALID_RESPONSE")
        writer.answers.remove(5)
        clock.advance(Duration.ofSeconds(30))
        runner.attempt(author.id, week.start)
        assertThat(report.row(author, week)).containsEntry("letter_status", "DONE")
    }

    @Test
    fun `SC-007 로그와 이벤트 기록에 수치와 편지가 없다`(output: CapturedOutput) {
        val author = authorWith(posts = 3, comments = 17)

        job.tick()
        report.awaitLetterStatus(author, week, "DONE")
        safety.awaitListenersIdle()

        val letter = report.row(author, week)!!["letter"] as String
        assertThat(output.all).doesNotContain(letter).doesNotContain("댓글 17개")
        val events =
            jdbcTemplate.queryForList(
                "select serialized_event from event_publication where event_type like '%WeeklyReportPublished' " +
                    "and serialized_event like ?",
                String::class.java,
                "%\"memberId\":${author.id}%",
            )
        assertThat(events).isNotEmpty().allSatisfy {
            assertThat(it).doesNotContain("postCount", "receivedComments", "letter", "17")
        }
    }
}
