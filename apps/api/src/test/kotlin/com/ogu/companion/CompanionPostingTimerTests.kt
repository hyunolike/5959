package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.companion.application.PostingScheduler
import com.ogu.shared.schedule.Cancellable
import com.ogu.shared.schedule.DelayScheduler
import com.ogu.shared.schedule.DueTimers
import com.ogu.support.CompanionFixture
import com.ogu.support.CompanionSettings
import com.ogu.support.CompanionTestConfiguration
import com.ogu.support.FutureClockSchedulers
import com.ogu.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * AI 친구의 글쓰기 타이머(018 US4, research R3). 1분마다 DB를 읽지 않고 조용한 시간이 끝나는 때에만 깬다. 타이머는 시계를
 * 움직일 때 같은 스레드에서 울리게 바꿔 끼운다. 반응의 주기 작업은 끄고(다른 테스트의 글을 맡지 않게) 글쓰기의 것만 켠다.
 */
@SpringBootTest(
    properties = [
        CompanionSettings.ENABLED,
        CompanionSettings.NO_SCHEDULER,
        CompanionSettings.LONG_TOKEN,
        CompanionSettings.NO_LIMIT,
        CompanionSettings.POSTING,
        FutureClockSchedulers.REPORT,
        FutureClockSchedulers.RAID,
        FutureClockSchedulers.EMOTION,
        FutureClockSchedulers.SAFETY,
        FutureClockSchedulers.RECOMMEND,
        FutureClockSchedulers.RECOMMEND_BACKFILL,
    ],
)
@Import(
    TestcontainersConfiguration::class,
    CompanionTestConfiguration::class,
    CompanionPostingTimerTests.ManualTimers::class,
)
class CompanionPostingTimerTests : CompanionPostingTestBase() {
    @Autowired
    lateinit var scheduler: PostingScheduler

    @Autowired
    lateinit var delays: SteppingScheduler

    @Test
    fun `US4-AC7 조용한 6시간이 지나면 글쓰기 타이머가 울린다`() {
        postOf(members.onboarded())
        val quietAt = clock.instant().plus(QUIET)
        awaitArmedAt(quietAt)

        delays.advance(QUIET.minusMinutes(1))
        assertThat(aiPosts()).isEmpty()

        delays.advance(Duration.ofMinutes(1))
        assertThat(aiPosts()).hasSize(1)
        // 방금 AI 친구의 글이 올라왔으니 다시 6시간 뒤다
        awaitArmedAt(clock.instant().plus(QUIET))
    }

    @Test
    fun `새 글이 오면 타이머가 미뤄진다`() {
        postOf(members.onboarded())
        awaitArmedAt(clock.instant().plus(QUIET))

        delays.advance(Duration.ofHours(3))
        postOf(members.onboarded())
        val postponed = clock.instant().plus(QUIET)
        awaitArmedAt(postponed)

        // 처음 걸렸던 시각에는 울리지 않는다
        delays.advance(Duration.ofHours(3))
        assertThat(aiPosts()).isEmpty()
        assertThat(delays.pending).isEqualTo(1)

        delays.advance(Duration.ofHours(3))
        assertThat(aiPosts()).hasSize(1)
    }

    @Test
    fun `조용한 시간이 시간대 밖에서 끝나면 다음 날 시작 시각에 건다`() {
        companions.quietDay(hour = 20)
        postOf(members.onboarded())
        val nextMorning = clock.instant().plus(Duration.ofHours(12))

        awaitArmedAt(nextMorning)
    }

    @Test
    fun `그날 쓸 친구가 없으면 다음 날 시작 시각에 건다`() {
        // 이날에 글이 있어야 다른 테스트의 조용한 날(quietDay)이 아래 기록이 있는 이날로 오지 않는다
        postOf(members.onboarded())
        val today = clock.instant()
        companions.memberIdByCompanion.keys.forEach { companionId ->
            jdbcTemplate.update(
                "insert into companion_post (companion_id, day, topic, comment_tone, status, give_up_reason, " +
                    "created_at, completed_at) values (?, ?, 'test', 'COMFORT_ME', 'GIVEN_UP', 'FAILED', ?, ?)",
                companionId,
                Date.valueOf(today.atZone(CompanionFixture.SEOUL).toLocalDate()),
                Timestamp.from(today),
                Timestamp.from(today),
            )
        }

        scheduler.rearmAfterCommit()

        assertThat(scheduler.nextAt).isEqualTo(today.plus(Duration.ofDays(1)))
    }

    private fun awaitArmedAt(at: Instant) {
        val expected = at.truncatedTo(ChronoUnit.MICROS)
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until {
            scheduler.nextAt == expected
        }
    }

    /** 타이머가 시계를 움직일 때 같은 스레드에서 울리게 바꿔 끼운다. 걸기는 리스너의 스레드에서도 오므로 잠근다. */
    @TestConfiguration(proxyBeanMethods = false)
    class ManualTimers {
        @Bean
        fun steppingScheduler(clock: MutableClock): SteppingScheduler = SteppingScheduler(clock)

        @Bean
        @Primary
        fun manualDueTimers(
            clock: MutableClock,
            scheduler: SteppingScheduler,
        ): DueTimers = DueTimers(clock, scheduler) {}
    }

    /** 시계를 움직일 때 차례가 된 일을 부르는 스케줄러. 여러 스레드가 걸어도 된다. */
    class SteppingScheduler(
        private val clock: MutableClock,
    ) : DelayScheduler {
        private val tasks = mutableListOf<Pair<Instant, Runnable>>()

        val pending: Int
            get() = synchronized(tasks) { tasks.size }

        override fun schedule(
            delay: Duration,
            task: Runnable,
        ): Cancellable {
            val entry = clock.instant().plus(delay) to task
            synchronized(tasks) { tasks += entry }
            return Cancellable { synchronized(tasks) { tasks.remove(entry) } }
        }

        fun advance(duration: Duration) {
            clock.advance(duration)
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
}
