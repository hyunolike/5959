package com.ogu.emotion.application

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.TestMember
import com.ogu.support.bearer
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 018 T010(감정): 재시도 스케줄러를 켠 채, 주기 폴링이 아니라 타이머가 다음 시도 시각에 분석을 다시 시도한다.
 *
 * 타이머는 실제 스레드에서 시계가 가리키는 시각까지 기다린다. 테스트는 시계를 그 시각으로 옮긴 뒤 [RetryScheduler.rearm]으로
 * 다시 걸어(지난 시각이면 바로 돈다) 30초를 실제로 기다리지 않는다.
 */
@SpringBootTest(properties = ["ogu.events.resubmit.enabled=false"])
@Import(TestcontainersConfiguration::class, EmotionRetryTimerTests.ClockOverride::class)
class EmotionRetryTimerTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var retry: RetryScheduler

    @Autowired
    lateinit var monsterApi: MonsterApi

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var member: TestMember

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        member = MemberFixture(mockMvc).onboarded()
    }

    @Test
    fun `US4-AC1 새 글의 감정 분석과 몬스터가 1분 안에 생긴다`() {
        val postId = createPost("[불안:낮음] 내일 발표가 걱정돼요")

        await().atMost(Duration.ofMinutes(1)).pollInterval(POLL).until {
            monsterApi.findByPostIds(listOf(postId)).isNotEmpty()
        }
    }

    @Test
    fun `US4-AC2 실패한 분석은 30초 뒤, 그다음 60초 뒤 타이머가 다시 시도한다`() {
        val createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val postId = createPost("[실패:2] 오늘은 아무것도 하기 싫다")

        // 첫 시도(리스너)가 실패하면 리스너가 타이머를 30초 뒤로 건다
        awaitAttempts(postId, 1)
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { retry.nextAt == createdAt.plusSeconds(30) }

        // 30초가 되면 타이머가 두 번째 시도를 하고, 실패하면 60초 뒤로 다시 건다
        clock.moveTo(createdAt.plusSeconds(30))
        retry.rearm()
        awaitAttempts(postId, 2)
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { retry.nextAt == createdAt.plusSeconds(90) }

        // 세 번째 시도는 성공하고 몬스터가 생긴다. 더 걸 시각이 없다
        clock.moveTo(createdAt.plusSeconds(90))
        retry.rearm()
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { monsterApi.findByPostIds(listOf(postId)).isNotEmpty() }
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { retry.nextAt == null }
    }

    private fun createPost(content: String): Long {
        val body = mapOf("content" to content, "commentTone" to "COMFORT_ME")
        val response =
            mockMvc
                .perform(
                    post("/api/v1/posts")
                        .bearer(member.accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return jsonMapper
            .readTree(response)
            .get("data")
            .get("postId")
            .asLong()
    }

    private fun awaitAttempts(
        postId: Long,
        attempts: Int,
    ) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).untilAsserted {
            val rows = jdbcTemplate.queryForList("select attempts from emotion_analysis where post_id = ?", postId)
            assertThat(rows.map { it["attempts"] }).containsExactly(attempts)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ClockOverride {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))
    }

    companion object {
        private val AWAIT_LIMIT: Duration = Duration.ofSeconds(10)
        private val POLL: Duration = Duration.ofMillis(50)
    }
}
