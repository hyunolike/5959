package com.ogu.recommend

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.post.PostWritten
import com.ogu.recommend.application.ClaimOutcome
import com.ogu.recommend.application.EmbeddingBackfill
import com.ogu.recommend.application.EmbeddingRunner
import com.ogu.recommend.application.EmbeddingStore
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.RecommendFixture
import com.ogu.support.SafetyFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * T007, T012: 임베딩 처리 일정과 이미 있는 글 처리(007 US2, US3, US4, research R4, R7). 이 컨텍스트의 시계를 테스트가
 * 움직이고 주기 작업을 끈 채 실행기를 직접 부른다. 가짜 임베더는 `[임베딩실패:N]`이면 처음 N번만 실패한다.
 */
@SpringBootTest(
    properties = ["ogu.recommend.retry.scheduler-enabled=false", "ogu.recommend.backfill.enabled=false"],
)
@Import(TestcontainersConfiguration::class, EmbeddingPipelineTests.ClockOverride::class)
@ExtendWith(OutputCaptureExtension::class)
class EmbeddingPipelineTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var runner: EmbeddingRunner

    @Autowired
    lateinit var store: EmbeddingStore

    @Autowired
    lateinit var backfill: EmbeddingBackfill

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var transaction: TransactionTemplate

    @Autowired
    lateinit var clock: MutableClock

    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture
    private lateinit var safety: SafetyFixture
    private lateinit var recommend: RecommendFixture

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        recommend = RecommendFixture(jdbcTemplate)
    }

    @Test
    fun `US2-AC1 임베딩이 실패해도 글 쓰기는 201이고 실패의 분류만 남는다`() {
        val postId = coreLoop.createPost(members.onboarded(), "[실패] [임베딩실패] 저장은 되는 글")

        recommend.awaitAttempts(postId, 1)

        val row = recommend.row(postId)!!
        assertThat(row).containsEntry("status", "PENDING").containsEntry("has_embedding", false)
        assertThat(row).containsEntry("last_error", "UPSTREAM_ERROR").containsEntry("model", null)
    }

    @Test
    fun `US2-AC4 실패하면 간격을 늘려 다시 시도하고 성공하면 DONE이 된다`() {
        val postId = coreLoop.createPost(members.onboarded(), "[실패] [임베딩실패:2] ${recommend.topic()} 두 번 실패할 글")
        recommend.awaitAttempts(postId, 1)
        assertThat(nextAttemptIn(postId)).isEqualTo(Duration.ofSeconds(30))

        // 아직 차례가 아니면 맡지 않는다
        clock.advance(Duration.ofSeconds(29))
        runner.attempt(postId)
        assertThat(recommend.row(postId)).containsEntry("attempts", 1)

        clock.advance(Duration.ofSeconds(1))
        runner.attempt(postId)
        assertThat(recommend.row(postId)).containsEntry("attempts", 2).containsEntry("status", "PENDING")
        assertThat(nextAttemptIn(postId)).isEqualTo(Duration.ofSeconds(60))

        clock.advance(Duration.ofSeconds(60))
        runner.attempt(postId)

        val row = recommend.row(postId)!!
        assertThat(row).containsEntry("status", "DONE").containsEntry("has_embedding", true)
        assertThat(row).containsEntry("model", "fake-embedder").containsEntry("last_error", null)
        assertThat(row).containsEntry("attempts", 3).containsEntry("embedded_seq", 1)
    }

    @Test
    fun `US2-AC5 24시간 동안 실패하면 그만두고 더 시도하지 않는다`() {
        val postId = coreLoop.createPost(members.onboarded(), "[실패] [임베딩실패] 계속 실패할 글")
        recommend.awaitAttempts(postId, 1)

        clock.advance(Duration.ofHours(24).minusSeconds(1))
        runner.attempt(postId)
        assertThat(recommend.row(postId)).containsEntry("status", "PENDING").containsEntry("attempts", 2)

        clock.advance(Duration.ofMinutes(10))
        runner.attempt(postId)
        assertThat(recommend.row(postId)).containsEntry("status", "GIVEN_UP").containsEntry("attempts", 2)

        clock.advance(Duration.ofHours(1))
        runner.attempt(postId)
        assertThat(recommend.row(postId)).containsEntry("status", "GIVEN_UP").containsEntry("attempts", 2)
    }

    @Test
    fun `US3-AC3 글을 고치면 다시 처리하고 US3-AC4 그동안 이전 값이 남는다`() {
        val author = members.onboarded()
        val postId = coreLoop.createPost(author, "[실패] ${recommend.topic()} 처음 내용")
        recommend.awaitEmbedded(postId)

        coreLoop
            .updatePost(author, postId, mapOf("content" to "[실패] [임베딩실패:1] ${recommend.topic()} 고친 내용"))
            .andExpect(status().is2xxSuccessful)
        recommend.awaitAttempts(postId, 1)

        // 다시 기다리는 동안에도 이전 값으로 추천을 찾을 수 있다
        val pending = recommend.row(postId)!!
        assertThat(pending).containsEntry("status", "PENDING").containsEntry("has_embedding", true)
        assertThat(pending).containsEntry("requested_seq", 2).containsEntry("embedded_seq", 1)

        clock.advance(Duration.ofSeconds(30))
        runner.attempt(postId)

        assertThat(recommend.row(postId)).containsEntry("status", "DONE").containsEntry("embedded_seq", 2)
    }

    @Test
    fun `맡은 뒤 글이 고쳐지면 늦게 온 이전 결과를 버린다`() {
        val postId = safety.insertPost(members.onboarded(), "늦은 결과를 받을 글")
        val authorId = authorOf(postId)
        store.request(postId, authorId)
        val claimed = store.claim(postId) as ClaimOutcome.Claimed

        // 호출이 돌아오기 전에 글이 고쳐졌다
        store.request(postId, authorId)
        store.recordSuccess(claimed.attempt.claim, "fake-embedder", FloatArray(DIMENSIONS) { 0.1f })

        val row = recommend.row(postId)!!
        assertThat(row).containsEntry("status", "PENDING").containsEntry("has_embedding", false)
        assertThat(row).containsEntry("requested_seq", 2).containsEntry("attempts", 0)
        // 늦게 온 실패도 새 요청을 건드리지 않는다
        store.recordFailure(claimed.attempt.claim, "TIMEOUT")
        assertThat(recommend.row(postId)).containsEntry("last_error", null)
    }

    @Test
    fun `US3-AC1 글을 지우면 값이 없어지고, 처리 전에 지운 글은 보내지 않고 닫는다`() {
        val author = members.onboarded()
        val embedded = coreLoop.createPost(author, "[실패] ${recommend.topic()} 지워질 글")
        recommend.awaitEmbedded(embedded)
        coreLoop.removePost(author, embedded).andExpect(status().isNoContent)
        recommend.awaitGone(embedded)

        val pending = safety.insertPost(author, "처리 전에 지워질 글")
        store.request(pending, author.id)
        jdbcTemplate.update("update posts set deleted_at = now() where id = ?", pending)

        assertThat(store.claim(pending)).isEqualTo(ClaimOutcome.Closed)
        assertThat(recommend.row(pending)).isNull()
    }

    @Test
    fun `같은 이벤트가 다시 와도 행은 하나이고 SC-008 로그와 이벤트 기록에 본문이 없다`(output: CapturedOutput) {
        val author = members.onboarded()
        val postId = coreLoop.createPost(author, "[실패] ${recommend.topic()} $MARKER")
        recommend.awaitEmbedded(postId)

        transaction.executeWithoutResult { events.publishEvent(PostWritten(postId, author.id, edited = false)) }
        safety.awaitListenersIdle()
        recommend.awaitEmbedded(postId)

        assertThat(rowCount(postId)).isEqualTo(1)
        assertThat(output.all).doesNotContain(MARKER)
        val published =
            jdbcTemplate.queryForList(
                "select serialized_event from event_publication where serialized_event like ?",
                String::class.java,
                "%\"postId\":$postId%",
            )
        assertThat(published).isNotEmpty().allSatisfy { assertThat(it).doesNotContain(MARKER, "content", "embedding") }
    }

    @Test
    fun `US4-AC1 이미 있는 글을 대상에 올리고 US4-AC3 다시 돌려도 처리한 글을 다시 보내지 않는다`() {
        val author = members.onboarded()
        val olds = (1..3).map { safety.insertPost(author, "추천이 생기기 전의 글 $it ${recommend.topic()}") }
        assertThat(olds.map(recommend::row)).containsOnlyNulls()

        assertThat(backfill.run()).isGreaterThanOrEqualTo(3)

        assertThat(olds.map(recommend::status)).containsOnly("PENDING")
        olds.take(2).forEach(runner::attempt)
        assertThat(olds.take(2).map(recommend::status)).containsOnly("DONE")

        // 중간에 멈췄다 다시 떠도 끝난 글은 그대로이고 남은 글만 이어서 한다
        backfill.run()
        assertThat(olds.take(2).map { recommend.row(it)!!["attempts"] }).containsOnly(1)
        assertThat(olds.map { recommend.row(it)!!["requested_seq"] }).containsOnly(1)
        runner.attempt(olds[2])
        assertThat(recommend.status(olds[2])).isEqualTo("DONE")
        // 지운 글은 올리지 않는다
        val deleted = safety.insertPost(author, "지운 글")
        jdbcTemplate.update("update posts set deleted_at = now() where id = ?", deleted)
        backfill.run()
        assertThat(recommend.row(deleted)).isNull()
    }

    @Test
    fun `US4-AC2 밀린 글이 있어도 새로 쓴 글은 바로 처리된다`() {
        val author = members.onboarded()
        (1..30).forEach { safety.insertPost(author, "밀린 글 $it") }
        backfill.run()

        val fresh = coreLoop.createPost(members.onboarded(), "[실패] ${recommend.topic()} 방금 쓴 글")

        recommend.awaitEmbedded(fresh)
    }

    @Test
    fun `모델이 바뀌면 옛 모델로 만든 값을 다시 만들게 한다`() {
        val postId = coreLoop.createPost(members.onboarded(), "[실패] ${recommend.topic()} 옛 모델의 글")
        recommend.awaitEmbedded(postId)
        jdbcTemplate.update("update post_embedding set model = 'old-model' where post_id = ?", postId)

        backfill.run()

        assertThat(recommend.row(postId)).containsEntry("status", "PENDING").containsEntry("requested_seq", 2)
        runner.attempt(postId)
        assertThat(recommend.row(postId)).containsEntry("status", "DONE").containsEntry("model", "fake-embedder")
    }

    private fun nextAttemptIn(postId: Long): Duration {
        val next = (recommend.row(postId)!!["next_attempt_at"] as Timestamp).toInstant()
        return Duration.between(clock.instant(), next)
    }

    private fun authorOf(postId: Long): Long =
        jdbcTemplate.queryForObject("select author_id from posts where id = ?", Long::class.java, postId)!!

    private fun rowCount(postId: Long): Int =
        jdbcTemplate.queryForObject("select count(*) from post_embedding where post_id = ?", Int::class.java, postId)!!

    @TestConfiguration(proxyBeanMethods = false)
    class ClockOverride {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS))
    }

    private companion object {
        const val MARKER = "오구임베딩본문표지"
        const val DIMENSIONS = 2048
    }
}
