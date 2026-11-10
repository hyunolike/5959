package com.ogu.report

import com.ogu.TestcontainersConfiguration
import com.ogu.report.application.WeekRange
import com.ogu.report.application.WeeklyReportScheduler
import com.ogu.shared.schedule.Cancellable
import com.ogu.shared.schedule.DelayScheduler
import com.ogu.shared.schedule.DueTimers
import com.ogu.support.FutureClockSchedulers
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.QueryCounter
import com.ogu.support.ReportFixture
import com.ogu.support.ReportTestConfiguration
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * 주간 리포트와 편지가 타이머로 깨는 경로(018 US4, research R3). 다른 리포트 테스트와 달리 `ogu.report.scheduler-enabled`를
 * 켠다. 타이머는 [SteppedScheduler]에 걸려, 테스트가 시계를 움직일 때만 같은 스레드에서 돈다.
 *
 * 시계는 다른 컨텍스트의 주와 겹치지 않게 10년 뒤에서 시작한다. 다른 테스트가 남긴 기다리는 편지는 테스트마다 먼저 닫는다.
 * 남겨 두면 이 컨텍스트의 시계로는 모두 차례가 지나, 편지 타이머가 그것들 먼저 1초마다 한 묶음씩 처리한다(그것을 확인할
 * 다른 테스트는 이미 끝났다). 한 차례의 한도는 2로 줄여 두었다.
 */
@SpringBootTest(
    properties = [
        FutureClockSchedulers.RAID,
        FutureClockSchedulers.EMOTION,
        FutureClockSchedulers.SAFETY,
        FutureClockSchedulers.RECOMMEND,
        FutureClockSchedulers.RECOMMEND_BACKFILL,
        "ogu.report.max-per-tick=2",
    ],
)
@Import(
    TestcontainersConfiguration::class,
    ReportTestConfiguration::class,
    WeeklyReportScheduleTests.SteppedTimers::class,
)
class WeeklyReportScheduleTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var timers: DueTimers

    @Autowired
    lateinit var scheduler: SteppedScheduler

    @Autowired
    lateinit var reportScheduler: WeeklyReportScheduler

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
        val far = Instant.now().plus(FAR_AHEAD)
        if (clock.instant() < far) clock.moveTo(far)
        week = report.newWeek()
        jdbcTemplate.update(
            "update weekly_report set letter_status = 'GIVEN_UP', letter_next_attempt_at = null " +
                "where letter_status = 'PENDING'",
        )
    }

    private fun publishAt(week: WeekRange): Instant =
        week.start
            .atTime(LocalTime.of(5, 0))
            .atZone(WeekRange.SEOUL)
            .toInstant()

    private fun at(day: Long) =
        week.start
            .plusDays(day)
            .atTime(LocalTime.NOON)
            .atZone(WeekRange.SEOUL)
            .toInstant()

    /** 기동을 흉내 낸다. 모든 타이머가 지금 시계로 다음 시각을 다시 정하고, 차례가 된 것은 바로 돈다. */
    private fun boot() {
        timers.rearmAll()
        scheduler.runDue()
    }

    /** [target] 주의 화요일에 글을 하나 쓴 회원. */
    private fun authorIn(target: WeekRange): TestMember {
        val author = members.onboarded()
        report.post(author, target.from.plus(Duration.ofDays(1)))
        return author
    }

    @Test
    fun `US4-AC5 월요일 05시에 리포트와 알림이 나간다`() {
        // 지금은 이번 주 수요일이다. 지난주를 다 훑었으니 다음에 깨는 것은 다음 주 월요일 05:00이다
        boot()
        val thisWeek = WeekRange(week.start.plusWeeks(1))
        val author = authorIn(thisWeek)
        val monday = publishAt(WeekRange(thisWeek.start.plusWeeks(1)))
        assertThat(scheduler.times()).contains(monday)

        scheduler.moveTo(monday.minusSeconds(1))
        assertThat(report.row(author, thisWeek)).isNull()
        // 발행 시각 전에는 시계로만 정한다. DB에 묻지 않는다
        val (next, queries) = QueryCounter.count { reportScheduler.nextDue() }
        assertThat(next).isEqualTo(monday)
        assertThat(queries).isZero()

        scheduler.moveTo(monday)
        report.awaitNotification(author)
        report.awaitLetterStatus(author, thisWeek, "DONE")
        safety.awaitListenersIdle()
        assertThat(scheduler.times()).contains(monday.plus(Duration.ofDays(7)))
    }

    @Test
    fun `기동이 늦어도 밀린 주를 채운다`() {
        val author = authorIn(week)
        // 월요일 05:00에 내려가 있다가 일요일 밤에야 떴다
        val thisWeek = WeekRange(week.start.plusWeeks(1))
        clock.moveTo(thisWeek.until.minus(Duration.ofHours(1)))

        boot()

        report.awaitNotification(author)
        assertThat(report.row(author, week)).isNotNull()
        assertThat(scheduler.times()).contains(publishAt(WeekRange(thisWeek.start.plusWeeks(1))))
        safety.awaitListenersIdle()
    }

    @Test
    fun `훑는 중이면 1분 뒤에 이어 가고 다 훑으면 다음 주 월요일에 깬다`() {
        val authors = (1..3).map { authorIn(week) }
        val nextMonday = publishAt(WeekRange(week.start.plusWeeks(2)))

        boot()
        // 한 차례의 한도(2)에서 멈췄다
        assertThat(authors.count { report.row(it, week) != null }).isEqualTo(2)
        assertThat(scheduler.times()).contains(clock.instant().plus(Duration.ofMinutes(1))).doesNotContain(nextMonday)

        scheduler.advance(Duration.ofMinutes(1))

        authors.forEach { report.awaitNotification(it) }
        assertThat(scheduler.times()).contains(nextMonday)
        safety.awaitListenersIdle()
    }

    @Test
    fun `US4-AC2 실패한 편지는 30초 뒤, 그다음 60초 뒤 다시 시도한다`() {
        // 쓴 글이 7이면 가짜 편지 쓰기가 두 번 실패한 뒤 성공한다
        val author = members.onboarded()
        repeat(7) { report.post(author, at(1).plusSeconds(it.toLong())) }

        boot()
        report.awaitLetterAttempts(author, week, 1)
        // 첫 시도를 한 리스너가 타이머를 다시 건다
        safety.awaitListenersIdle()
        assertThat(scheduler.times()).contains(clock.instant().plusSeconds(30))

        scheduler.advance(Duration.ofSeconds(29))
        assertThat(report.row(author, week)).containsEntry("letter_attempts", 1)
        scheduler.advance(Duration.ofSeconds(1))
        assertThat(report.row(author, week))
            .containsEntry("letter_attempts", 2)
            .containsEntry("letter_status", "PENDING")

        scheduler.advance(Duration.ofSeconds(59))
        assertThat(report.row(author, week)).containsEntry("letter_attempts", 2)
        scheduler.advance(Duration.ofSeconds(1))
        assertThat(report.row(author, week))
            .containsEntry("letter_status", "DONE")
            .containsEntry("letter_attempts", 3)
            .containsEntry("letter_next_attempt_at", null)
    }

    /** 타이머를 [SteppedScheduler]에 거는 [DueTimers]. 앱의 것 대신 쓰인다. */
    @TestConfiguration(proxyBeanMethods = false)
    class SteppedTimers {
        @Bean
        fun steppedScheduler(clock: MutableClock): SteppedScheduler = SteppedScheduler(clock)

        @Bean
        @Primary
        fun steppedDueTimers(
            clock: MutableClock,
            scheduler: SteppedScheduler,
        ): DueTimers = DueTimers(clock, scheduler) {}
    }

    private companion object {
        val FAR_AHEAD: Duration = Duration.ofDays(3650)
    }
}

/**
 * 시계를 움직일 때 차례가 된 일을 같은 스레드에서 부르는 스케줄러. 커밋 뒤 리스너가 다른 스레드에서 타이머를 다시 걸므로
 * 목록은 잠가서 다루고, 일은 잠금 밖에서 부른다(일이 다시 걸 수 있다).
 */
class SteppedScheduler(
    private val clock: MutableClock,
) : DelayScheduler {
    private val tasks = mutableListOf<Pair<Instant, Runnable>>()

    override fun schedule(
        delay: Duration,
        task: Runnable,
    ): Cancellable {
        val entry = clock.instant().plus(delay) to task
        synchronized(tasks) { tasks += entry }
        return Cancellable { synchronized(tasks) { tasks.remove(entry) } }
    }

    /** 걸려 있는 시각들. */
    fun times(): List<Instant> = synchronized(tasks) { tasks.map { it.first } }

    fun advance(duration: Duration) = moveTo(clock.instant().plus(duration))

    fun moveTo(instant: Instant) {
        clock.moveTo(instant)
        runDue()
    }

    /** 지금 시계로 차례가 된 일을 이른 것부터 모두 부른다. */
    fun runDue() {
        while (true) {
            val due =
                synchronized(tasks) {
                    tasks
                        .filter { !it.first.isAfter(clock.instant()) }
                        .minByOrNull { it.first }
                        ?.also { tasks.remove(it) }
                } ?: return
            due.second.run()
        }
    }
}
