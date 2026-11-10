package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.safety.application.RiskRetryScheduler
import com.ogu.shared.schedule.DueTimers
import com.ogu.support.CoreLoopFixture
import com.ogu.support.FutureClockSchedulers
import com.ogu.support.ManualTimerScheduler
import com.ogu.support.ManualTimersConfiguration
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.SafetyFixture
import com.ogu.support.SafetyFixture.Companion.SAFE_TEXT
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * T010(018 research R3): 위험 분류 재시도가 주기 없이 가장 이른 다음 시각에만 깨는지 본다. 이 컨텍스트만 위험 분류
 * 스케줄러를 켜고, 타이머는 테스트가 시계를 움직일 때만 울린다([ManualTimersConfiguration]).
 */
@SpringBootTest(
    properties = [
        FutureClockSchedulers.EMOTION,
        FutureClockSchedulers.RECOMMEND,
        FutureClockSchedulers.RECOMMEND_BACKFILL,
        FutureClockSchedulers.RAID,
        FutureClockSchedulers.REPORT,
    ],
)
@Import(TestcontainersConfiguration::class, ManualTimersConfiguration::class)
class RiskRetryTimerTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var scheduler: ManualTimerScheduler

    @Autowired
    lateinit var timers: DueTimers

    @Autowired
    lateinit var retry: RiskRetryScheduler

    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture
    private lateinit var safety: SafetyFixture

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        // 다른 테스트가 남긴 기다리는 판정이 다음 시각에 끼지 않게 한다
        jdbcTemplate.update(
            "update risk_assessment set status = 'SUPERSEDED', completed_at = now() where status = 'PENDING'",
        )
        retry.rearm()
    }

    @Test
    fun `US4-AC1 기동 때 기다리던 판정은 1분 안에 타이머가 처리하고 할 일이 없으면 걸지 않는다`() {
        val author = members.onboarded()
        val postId = safety.insertPost(author, SAFE_TEXT)
        insertPending(postId, author.id)
        assertThat(retry.nextAt).isNull()

        // 기동 때 모든 타이머를 DB에서 다시 계산한다. 첫 시도를 할 리스너가 없어도 타이머가 맡는다
        timers.rearmAll()
        // 직전에 돌았다면 1초는 쉬므로(DueTimer의 최소 간격) 늦어도 1초 뒤다
        assertThat(retry.nextAt).isNotNull().isBeforeOrEqualTo(clock.instant().plusSeconds(1))
        scheduler.advance(Duration.ofSeconds(1))

        assertThat(assessment(postId)).containsEntry("status", "DONE").containsEntry("attempts", 1)
        assertThat(retry.nextAt).isNull()
    }

    @Test
    fun `US4-AC2 실패한 분류는 30초 뒤, 그다음 60초 뒤에 타이머가 다시 시도한다`() {
        val startedAt = clock.instant()

        val postId = coreLoop.createPost(members.onboarded(), "[실패] [위험분류실패:2] $SAFE_TEXT")

        // 첫 시도는 커밋 뒤 리스너가 하고, 실패하면 그 백오프 시각에 타이머를 건다
        awaitNextAt(startedAt.plusSeconds(30))
        assertThat(assessment(postId)).containsEntry("attempts", 1).containsEntry("status", "PENDING")

        scheduler.advance(Duration.ofSeconds(29))
        assertThat(assessment(postId)).containsEntry("attempts", 1)

        scheduler.advance(Duration.ofSeconds(1))
        assertThat(assessment(postId)).containsEntry("attempts", 2).containsEntry("status", "PENDING")
        assertThat(retry.nextAt).isEqualTo(startedAt.plusSeconds(30 + 60))

        scheduler.advance(Duration.ofSeconds(60))
        assertThat(assessment(postId)).containsEntry("attempts", 3).containsEntry("status", "DONE")
        assertThat(retry.nextAt).isNull()
    }

    private fun insertPending(
        postId: Long,
        authorId: Long,
    ) {
        val updatedAt =
            jdbcTemplate.queryForObject("select updated_at from posts where id = ?", Timestamp::class.java, postId)
        val now = Timestamp.from(clock.instant())
        jdbcTemplate.update(
            """
            insert into risk_assessment (target_type, target_id, post_id, author_id, content_version, keyword_level,
                                         level, status, next_attempt_at, created_at)
            values ('POST', ?, ?, ?, ?, 'NONE', 'NONE', 'PENDING', ?, ?)
            """.trimIndent(),
            postId,
            postId,
            authorId,
            updatedAt,
            now,
            now,
        )
    }

    private fun assessment(postId: Long): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            "select * from risk_assessment where target_type = 'POST' and target_id = ? order by id desc limit 1",
            postId,
        )

    private fun awaitNextAt(expected: Instant) {
        await()
            .atMost(SafetyFixture.AWAIT_LIMIT)
            .pollInterval(SafetyFixture.POLL)
            .until { retry.nextAt == expected }
    }
}
