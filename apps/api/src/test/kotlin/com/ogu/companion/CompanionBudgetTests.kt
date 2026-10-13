package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.companion.application.DailyBudget
import com.ogu.support.CompanionFixture
import com.ogu.support.CompanionSettings
import com.ogu.support.CompanionTestConfiguration
import com.ogu.support.FutureClockSchedulers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.sql.Date
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 하루의 몫(009 US5-AC2, SC-007, research R8). 상한을 낮춰 띄우면 컨텍스트가 하나 더 생기므로, 테스트마다 아직 아무도 쓰지
 * 않은 날로 시계를 옮기고 그날의 몫을 두 번만 남겨 둔다. 끝나면 다시 새 날로 옮겨 뒤의 테스트가 몫이 떨어진 날에서
 * 시작하지 않게 한다.
 */
@SpringBootTest(
    properties = [
        CompanionSettings.ENABLED,
        CompanionSettings.NO_SCHEDULER,
        CompanionSettings.LONG_TOKEN,
        CompanionSettings.NO_LIMIT,
        FutureClockSchedulers.REPORT,
        FutureClockSchedulers.RAID,
        FutureClockSchedulers.EMOTION,
        FutureClockSchedulers.SAFETY,
        FutureClockSchedulers.RECOMMEND,
        FutureClockSchedulers.RECOMMEND_BACKFILL,
    ],
)
@Import(TestcontainersConfiguration::class, CompanionTestConfiguration::class)
class CompanionBudgetTests : CompanionTestBase() {
    @Autowired
    lateinit var budget: DailyBudget

    @BeforeEach
    fun leaveTwo() {
        companions.freshDay()
        companions.spendToday(SPENT)
    }

    @AfterEach
    fun leaveTheDay() {
        companions.freshDay()
    }

    /** 이 테스트가 오늘 쓴 몫. */
    private fun spent(): Int = companions.usedToday() - SPENT

    @Test
    fun `US5-AC2 하루의 몫이 둘 남았으면 글 셋 가운데 둘에만 반응하고 셋째는 DAILY_LIMIT이다`() {
        val author = members.onboarded()
        val posts = (1..3).map { postOf(author) }

        companions.pass(CompanionFixture.WAIT)
        posts.forEach { assertThat(runner.attempt(it)).isTrue() }

        assertThat(posts.take(2).map(::statusOf)).containsOnly("DONE")
        assertThat(skipReasonOf(posts[2])).isEqualTo("DAILY_LIMIT")
        assertThat(companions.comments(posts[2])).isEmpty()
        // 몫이 없으면 AI를 부르지 않는다
        assertThat(writer.callsOf(posts[2])).isZero()
        assertThat(spent()).isEqualTo(2)
        assertThat(companions.usedToday()).isEqualTo(CompanionFixture.LIMIT)
    }

    @Test
    fun `US5-AC2 실패한 시도도 상한에 든다`() {
        val author = members.onboarded()
        val failing = postOf(author, "[실패] [AI댓글실패:1] 한 번 실패하는 글")
        val other = postOf(author)

        companions.pass(CompanionFixture.WAIT)
        runner.attempt(failing)
        runner.attempt(other)
        assertThat(spent()).isEqualTo(2)
        assertThat(statusOf(other)).isEqualTo("DONE")
        assertThat(statusOf(failing)).isEqualTo("WAITING")

        // 다시 시도할 때가 됐지만 몫이 없다
        assertThat(reactAfter(failing, Duration.ofMinutes(1))).isTrue()

        assertThat(skipReasonOf(failing)).isEqualTo("DAILY_LIMIT")
        assertThat(writer.callsOf(failing)).isEqualTo(1)
        assertThat(spent()).isEqualTo(2)
    }

    @Test
    fun `US5-AC2 회로가 열려 호출이 나가지 않으면 몫을 돌려주고 시도로 세지 않는다`() {
        val postId = postOf(members.onboarded())
        writer.failures[postId] = EmotionAnalysisFailed.Kind.CIRCUIT_OPEN

        reactAfter(postId)

        val row = companions.reaction(postId)!!
        assertThat(row["status"]).isEqualTo("WAITING")
        assertThat(row["attempts"]).isEqualTo(0)
        assertThat(row["last_error"]).isEqualTo("CIRCUIT_OPEN")
        assertThat(spent()).isZero()

        // 회로가 닫히면 다음 차례에 반응한다
        writer.failures.remove(postId)
        reactAfter(postId, Duration.ofMinutes(1))
        assertThat(statusOf(postId)).isEqualTo("DONE")
        assertThat(spent()).isEqualTo(1)
    }

    @Test
    fun `SC-007 자정 전에 받은 몫은 자정 뒤에 돌려줘도 받은 날의 몫이 준다`() {
        val day = budget.reserveDay()!!
        val usedThatDay = usedOn(day)
        val seoul = ZoneId.of("Asia/Seoul")
        val nextMidnight = day.plusDays(1).atStartOfDay(seoul).toInstant()
        companions.pass(Duration.between(clock.instant(), nextMidnight).plusHours(1))
        val usedNextDay = usedOn(day.plusDays(1))

        budget.release(day)

        assertThat(usedOn(day)).isEqualTo(usedThatDay - 1)
        // 새 날의 몫을 줄이지 않는다. 줄면 그날 상한보다 한 번 더 청하게 된다
        assertThat(usedOn(day.plusDays(1))).isEqualTo(usedNextDay)
    }

    private fun usedOn(day: LocalDate): Int =
        jdbcTemplate
            .queryForList("select used from companion_daily_usage where day = ?", Int::class.java, Date.valueOf(day))
            .firstOrNull() ?: 0

    @Test
    fun `US5-AC2 한국 시간으로 날이 바뀌면 다시 반응한다`() {
        val author = members.onboarded()
        val today = (1..3).map { postOf(author) }
        companions.pass(CompanionFixture.WAIT)
        today.forEach { runner.attempt(it) }
        assertThat(skipReasonOf(today[2])).isEqualTo("DAILY_LIMIT")

        // 정오에서 13시간 뒤면 다음 날 새벽 1시다. 새 날의 몫은 하나도 쓰지 않았다
        companions.pass(Duration.ofHours(13))
        val tomorrow = postOf(author)
        reactAfter(tomorrow)

        assertThat(statusOf(tomorrow)).isEqualTo("DONE")
        assertThat(companions.usedToday()).isEqualTo(1)
        // 어제 몫이 없어 건너뛴 글은 다시 보지 않는다
        assertThat(runner.attempt(today[2])).isFalse()
        assertThat(skipReasonOf(today[2])).isEqualTo("DAILY_LIMIT")
    }

    @Test
    fun `SC-007 여러 실행기가 함께 받아도 하루의 몫을 넘지 않는다`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENCY)

        val granted =
            try {
                (1..CONCURRENCY)
                    .map {
                        executor.submit(
                            Callable {
                                start.await(5, TimeUnit.SECONDS)
                                budget.reserve()
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(20, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertThat(granted.count { it }).isEqualTo(2)
        assertThat(companions.usedToday()).isEqualTo(CompanionFixture.LIMIT)
        // 돌려주면 다시 받을 수 있다
        budget.release()
        assertThat(budget.reserve()).isTrue()
        assertThat(budget.reserve()).isFalse()
    }

    @Test
    fun `SC-007 돌려줘도 쓴 몫은 0 아래로 내려가지 않는다`() {
        // 아무것도 쓰지 않은 날
        companions.freshDay()

        assertThat(budget.reserve()).isTrue()
        repeat(3) { budget.release() }

        assertThat(companions.usedToday()).isZero()
        assertThat(budget.reserve()).isTrue()
        assertThat(companions.usedToday()).isEqualTo(1)
    }

    private companion object {
        const val CONCURRENCY = 8

        /** 오늘의 몫을 둘만 남긴다. */
        const val SPENT = CompanionFixture.LIMIT - 2
    }
}
