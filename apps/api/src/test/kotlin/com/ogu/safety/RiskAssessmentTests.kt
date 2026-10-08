package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.safety.application.RiskClassificationRunner
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.SafetyFixture
import com.ogu.support.SafetyFixture.Companion.CRISIS_TEXT
import com.ogu.support.SafetyFixture.Companion.SAFE_TEXT
import com.ogu.support.ScriptedRiskClassifier
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T027: AI 위험 분류와 재시도(005 US2, research R3, R12). 재시도 스케줄러를 끄고 시계를 직접 움직이며 `runDue`를 부른다.
 * 가짜 분류기는 본문의 표지(`[위기]`, `[우려]`, `[위험분류실패:N]`)로 결과를 정한다.
 */
@SpringBootTest(properties = ["ogu.safety.retry.scheduler-enabled=false"])
@Import(TestcontainersConfiguration::class, RiskAssessmentTests.ClockOverride::class)
class RiskAssessmentTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var runner: RiskClassificationRunner

    @Autowired
    lateinit var classifier: ScriptedRiskClassifier

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture
    private lateinit var safety: SafetyFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
    }

    @Test
    fun `US2-AC1 AI가 실패해도 키워드 규칙으로 위기 판정하고 숨긴다`() {
        val author = members.onboarded()
        val other = members.onboarded()

        val postId = coreLoop.createPost(author, "[실패] [위험분류실패] $CRISIS_TEXT")

        // 저장 응답 시점에 이미 숨겨져 있다
        assertThat(safety.postState(postId)).containsEntry("hidden", true).containsEntry("risk_level", "CRISIS")
        assertThat(safety.feedPostIds(other)).doesNotContain(postId)
        coreLoop.detail(author, postId).andExpect(jsonPath("$.data.safety.level").value("CRISIS"))
        awaitAssessment(postId) { it["attempts"] == 1 }
        assertThat(assessment(postId)).containsEntry("status", "PENDING").containsEntry("last_error", "UPSTREAM_ERROR")
        safety.awaitNotifications(author, "SUPPORT_NOTICE", 1)
    }

    @Test
    fun `US2-AC2 AI가 위험 없음이어도 키워드가 위기면 위기이고 단계가 내려가지 않는다`() {
        val author = members.onboarded()

        val postId = coreLoop.createPost(author, "[실패] $CRISIS_TEXT")

        awaitAssessment(postId) { it["status"] == "DONE" }
        assertThat(assessment(postId))
            .containsEntry("keyword_level", "CRISIS")
            .containsEntry("ai_level", "NONE")
            .containsEntry("level", "CRISIS")
        assertThat(safety.postState(postId)).containsEntry("hidden", true).containsEntry("risk_level", "CRISIS")
    }

    @Test
    fun `US2-AC3 AI가 실패해도 글 저장은 201이고 일정이 30초, 60초 뒤로 밀리며 성공하면 DONE이다`() {
        val author = members.onboarded()
        val startedAt = clock.instant()

        val postId = coreLoop.createPost(author, "[실패] [위험분류실패:2] $SAFE_TEXT")

        coreLoop.detail(author, postId).andExpect(status().isOk)
        awaitAssessment(postId) { it["attempts"] == 1 }
        assertThat(nextAttemptAt(postId)).isEqualTo(startedAt.plusSeconds(30))

        // 아직 차례가 아니면 맡지 않는다
        clock.advance(Duration.ofSeconds(29))
        runner.runDue()
        assertThat(assessment(postId)).containsEntry("attempts", 1)

        clock.advance(Duration.ofSeconds(1))
        runner.runDue()
        assertThat(assessment(postId)).containsEntry("attempts", 2).containsEntry("status", "PENDING")
        assertThat(nextAttemptAt(postId)).isEqualTo(startedAt.plusSeconds(30 + 60))

        clock.advance(Duration.ofSeconds(60))
        runner.runDue()
        assertThat(assessment(postId))
            .containsEntry("attempts", 3)
            .containsEntry("status", "DONE")
            .containsEntry("level", "NONE")
        assertThat(assessment(postId)["last_error"]).isNull()
    }

    @Test
    fun `24시간이 지나면 FALLBACK으로 닫고 단계는 키워드 판정 그대로다`() {
        val author = members.onboarded()
        val postId = coreLoop.createPost(author, "[실패] [위험분류실패] $CRISIS_TEXT")
        awaitAssessment(postId) { it["attempts"] == 1 }

        clock.advance(Duration.ofHours(24))
        runner.runDue()

        assertThat(assessment(postId))
            .containsEntry("status", "FALLBACK")
            .containsEntry("level", "CRISIS")
        assertThat(assessment(postId)["ai_level"]).isNull()
        // 닫힌 판정은 다시 맡지 않는다
        val calls = classifier.callsFor("POST:$postId")
        clock.advance(Duration.ofMinutes(10))
        runner.runDue()
        assertThat(classifier.callsFor("POST:$postId")).isEqualTo(calls)
    }

    @Test
    fun `AI만 위기로 본 글은 분류가 끝난 뒤 숨겨지고 작성자에게 한 번 알린다`() {
        val author = members.onboarded()
        val other = members.onboarded()

        val postId = coreLoop.createPost(author, "[실패] [위기] 요즘 잠이 통 오지 않아요")

        awaitAssessment(postId) { it["status"] == "DONE" }
        assertThat(assessment(postId))
            .containsEntry("keyword_level", "NONE")
            .containsEntry("ai_level", "CRISIS")
            .containsEntry("level", "CRISIS")
        assertThat(safety.postState(postId)).containsEntry("hidden", true).containsEntry("hidden_reason", "RISK")
        assertThat(safety.feedPostIds(other)).doesNotContain(postId)
        safety.awaitNotifications(author, "SUPPORT_NOTICE", 1)
        assertThat(safety.notificationKeys(author, "SUPPORT_NOTICE")).containsExactly("RISK:POST:$postId:CRISIS")
    }

    @Test
    fun `AI만 우려로 본 글은 숨기지 않고 작성자에게 알린다`() {
        val author = members.onboarded()

        val postId = coreLoop.createPost(author, "[실패] [우려] 일이 손에 잡히지 않아요")

        awaitAssessment(postId) { it["status"] == "DONE" }
        assertThat(safety.postState(postId)).containsEntry("hidden", false).containsEntry("risk_level", "CONCERN")
        safety.awaitNotifications(author, "SUPPORT_NOTICE", 1)
        assertThat(safety.notificationKeys(author, "SUPPORT_NOTICE")).containsExactly("RISK:POST:$postId:CONCERN")
    }

    @Test
    fun `AI만 위기로 본 댓글도 분류가 끝난 뒤 숨겨진다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = coreLoop.postWithoutMonster(author)

        val commentId = coreLoop.comment(commenter, postId, "[위기] 저도 요즘 그래요")

        await().atMost(SafetyFixture.AWAIT_LIMIT).pollInterval(SafetyFixture.POLL).until {
            safety.commentState(commentId)["hidden"] == true
        }
        coreLoop.comments(author, postId).andExpect(jsonPath("$.data.items[0].content").value(null))
        safety.awaitNotifications(commenter, "SUPPORT_NOTICE", 1)
    }

    @Test
    fun `US2-AC5 분류하는 사이에 고친 글은 이전 내용의 결과를 버리고 고친 내용으로 다시 판정한다`() {
        val author = members.onboarded()
        val postId = coreLoop.createPost(author, "[실패] [위험분류실패:1] [위기] 고치기 전의 글")
        awaitAssessment(postId) { it["attempts"] == 1 }
        val staleId = assessment(postId)["id"]
        // 다음 시도에서 분류기가 답하기 직전에 작성자가 글을 고친다
        classifier.beforeAnswer("POST:$postId") {
            coreLoop.updatePost(author, postId, mapOf("content" to "[실패] $SAFE_TEXT")).andExpect(status().isNoContent)
        }

        clock.advance(Duration.ofSeconds(30))
        runner.runDue()

        // 이전 내용은 가짜 분류기가 위기로 답했지만, 그 결과로 숨기거나 알리지 않는다
        val stale = jdbcTemplate.queryForMap("select status, ai_level from risk_assessment where id = ?", staleId)
        assertThat(stale).containsEntry("status", "SUPERSEDED")
        assertThat(stale["ai_level"]).isNull()
        awaitAssessment(postId) { it["status"] == "DONE" }
        assertThat(assessment(postId)["id"]).isNotEqualTo(staleId)
        assertThat(assessment(postId)).containsEntry("level", "NONE")
        assertThat(safety.postState(postId)).containsEntry("hidden", false).containsEntry("risk_level", "NONE")
        safety.awaitListenersIdle()
        assertThat(safety.notificationKeys(author, "SUPPORT_NOTICE")).isEmpty()
    }

    @Test
    fun `지운 글의 판정은 분류 없이 닫는다`() {
        val author = members.onboarded()
        val postId = coreLoop.createPost(author, "[실패] [위험분류실패:1] $SAFE_TEXT")
        awaitAssessment(postId) { it["attempts"] == 1 }
        val calls = classifier.callsFor("POST:$postId")
        coreLoop.removePost(author, postId).andExpect(status().isNoContent)

        clock.advance(Duration.ofSeconds(30))
        runner.runDue()

        assertThat(assessment(postId)).containsEntry("status", "SUPERSEDED")
        assertThat(classifier.callsFor("POST:$postId")).isEqualTo(calls)
    }

    @Test
    fun `두 실행기가 같은 판정을 함께 맡지 않는다`() {
        val author = members.onboarded()
        val postId = coreLoop.createPost(author, "[실패] [위험분류실패:1] $SAFE_TEXT")
        awaitAssessment(postId) { it["attempts"] == 1 }
        clock.advance(Duration.ofSeconds(30))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    executor.submit(
                        Callable {
                            start.await()
                            runner.runDue()
                        },
                    )
                }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        // 처음 시도(실패) 한 번과 다시 시도 한 번뿐이다
        assertThat(classifier.callsFor("POST:$postId")).isEqualTo(2)
        assertThat(assessment(postId)).containsEntry("attempts", 2).containsEntry("status", "DONE")
    }

    private fun assessment(postId: Long): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            "select * from risk_assessment where target_type = 'POST' and target_id = ? order by id desc limit 1",
            postId,
        )

    private fun nextAttemptAt(postId: Long): Instant {
        val value = assessment(postId)["next_attempt_at"] as Timestamp
        return value.toInstant()
    }

    private fun awaitAssessment(
        postId: Long,
        condition: (Map<String, Any?>) -> Boolean,
    ) {
        await().atMost(SafetyFixture.AWAIT_LIMIT).pollInterval(SafetyFixture.POLL).until {
            condition(assessment(postId))
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ClockOverride {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS))
    }
}
