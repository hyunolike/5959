package com.ogu.shared.schedule

import com.ogu.TestcontainersConfiguration
import com.ogu.emotion.application.RetryScheduler
import com.ogu.support.AllThreadQueries
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.util.ClassUtils
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 018 T009(US4, research R3): 할 일이 없으면 주기 작업이 DB에 쿼리를 보내지 않는다. 그래야 Neon이 5분 뒤 잔다.
 *
 * 모든 주기 작업을 켠 컨텍스트다(스위치를 끄지 않는다). 회원이 떠난 상태를 만들려고 활동 창을 0으로 둔다
 * (`ActivityGate`가 늘 비활성). 타이머는 실제 시계로 기다리므로 10분을 다 기다리지 않고 둘로 나눠 본다.
 * - 짧은 시간(15초) 동안 테스트 밖의 스레드가 쿼리를 하나도 보내지 않는다. 1초, 10초, 30초 주기의 폴링이 남아 있으면 잡힌다.
 * - 모든 타이머의 다음 시각이 없거나 10분 뒤보다 늦다. 그 사이에 깨어 DB를 열 일이 없다.
 */
@SpringBootTest(properties = ["ogu.activity.window=0s"])
@Import(TestcontainersConfiguration::class, IdleDatabaseTests.OwnTimerThreads::class)
class IdleDatabaseTests {
    @Autowired
    lateinit var timers: DueTimers

    @Autowired
    lateinit var retry: RetryScheduler

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `US4-AC3 할 일이 없으면 마지막 요청 뒤 쿼리가 하나도 없다`() {
        // 같은 DB를 쓰는 다른 테스트가 남긴 기다리는 일을 치운다. 할 일이 있으면 타이머가 도는 것이 맞다
        clearLeftoverWork()
        timers.rearmAll()
        // 기동 때의 일(타이머 계산, 보스 생성, 백필 확인)이 끝나기를 기다린다
        Thread.sleep(SETTLE.toMillis())

        val (_, queries) = AllThreadQueries.record { Thread.sleep(IDLE.toMillis()) }

        // 테스트 JVM에는 앞선 테스트 클래스의 컨텍스트도 살아 있고 같은 DB를 쓴다. 이 컨텍스트의 타이머 스레드만 본다.
        assertThat(queries.filter { it.startsWith(OWN_TIMER_THREAD) }).isEmpty()
        val horizon = clock.instant().plus(Duration.ofMinutes(10))
        val soon = timers.nextTimes().filterValues { it != null && it.isBefore(horizon) }
        assertThat(soon).isEmpty()
    }

    @Test
    fun `US4-AC3 남은 주기 작업은 DB를 기다리지 않거나 회원이 쓰는 동안만 도는 것뿐이다`() {
        // 타이머 밖에 남은 @Scheduled. 하나라도 늘면 그 작업이 DB를 깨우는지 따져 보고 이 목록을 고친다.
        //  - Housekeeping: 하루 한 번 04:00
        //  - EventPublicationResubmitter: 회원이 최근에 쓴 동안만(ActivityGate)
        //  - RaidFlusher: 1초마다 돌지만 바뀐 회원이 없으면 DB를 열지 않는다(RaidDurabilityTests)
        val scheduled =
            context.beanDefinitionNames
                .mapNotNull { name -> context.getType(name) }
                .map { ClassUtils.getUserClass(it) }
                .filter { it.name.startsWith("com.ogu.") }
                .filter { type -> type.methods.any { it.isAnnotationPresent(Scheduled::class.java) } }
                .map { it.simpleName }
                .toSet()

        assertThat(scheduled).containsExactlyInAnyOrder(
            "Housekeeping",
            "EventPublicationResubmitter",
            "RaidFlusher",
        )
    }

    @Test
    fun `US4-AC4 기다리는 재시도가 있는 채 다시 뜨면 그 시각에 돈다`() {
        val dueAt: Instant = clock.instant().plus(Duration.ofMinutes(3)).truncatedTo(ChronoUnit.MICROS)
        val postId = insertPendingAnalysis(dueAt)

        // 기동 때와 같은 일: 모든 타이머의 다음 시각을 DB에서 다시 읽는다
        timers.rearmAll()

        await().atMost(Duration.ofSeconds(5)).until { retry.nextAt == dueAt }
        jdbcTemplate.update("delete from emotion_analysis where post_id = ?", postId)
        timers.rearmAll()
    }

    private fun clearLeftoverWork() {
        jdbcTemplate.update("delete from emotion_analysis where status = 'PENDING'")
        jdbcTemplate.update(
            "update risk_assessment set status = 'SUPERSEDED', completed_at = now() where status = 'PENDING'",
        )
        jdbcTemplate.update("update post_embedding set status = 'GIVEN_UP' where status = 'PENDING'")
        jdbcTemplate.update(
            "update weekly_report set letter_status = 'GIVEN_UP', letter_next_attempt_at = null " +
                "where letter_status = 'PENDING'",
        )
    }

    /** 분석 행만 만든다. 글과의 외래 키가 없고, 타이머는 이 표의 다음 시각만 본다. 시도하기 전에 지운다. */
    private fun insertPendingAnalysis(dueAt: Instant): Long {
        val postId = UNUSED_POST_ID
        jdbcTemplate.update(
            """
            insert into emotion_analysis (post_id, status, attempts, next_attempt_at, post_created_at)
            values (?, 'PENDING', 1, ?, now())
            """.trimIndent(),
            postId,
            Timestamp.from(dueAt),
        )
        return postId
    }

    /** 이 컨텍스트의 타이머 스레드에 따로 이름을 붙인다. */
    @TestConfiguration(proxyBeanMethods = false)
    class OwnTimerThreads {
        @Bean
        @Primary
        fun ownDueTimers(clock: Clock): DueTimers = DueTimers(clock, OWN_TIMER_THREAD)
    }

    companion object {
        private val SETTLE: Duration = Duration.ofSeconds(5)
        private val IDLE: Duration = Duration.ofSeconds(15)
        private const val OWN_TIMER_THREAD = "idle-test-timer-"
        private const val UNUSED_POST_ID = 9_000_000_001L
    }
}
