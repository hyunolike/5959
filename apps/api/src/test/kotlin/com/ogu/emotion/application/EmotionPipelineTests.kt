package com.ogu.emotion.application

import com.ogu.TestcontainersConfiguration
import com.ogu.emotion.AnalysisStatus
import com.ogu.emotion.EmotionApi
import com.ogu.emotion.EmotionType
import com.ogu.monster.MonsterApi
import com.ogu.monster.MonsterStatus
import com.ogu.monster.MonsterView
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.ScriptedEmotionAnalyzer
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
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T017: 글 작성 → 비동기 감정 분석 → 재시도 → 몬스터 생성(US1-AC4~AC6, FR-002~FR-005, research R2, R4).
 * 가짜 분석기(research R3)와 테스트가 움직이는 시계를 쓴다. 재시도 스케줄러는 끄고 [AnalysisRunner]를 직접 돌린다.
 * 비동기 결과는 짧은 간격으로 확인하되 상한(10초)을 두고 기다린다.
 */
@SpringBootTest(properties = ["ogu.emotion.retry.scheduler-enabled=false"])
@Import(TestcontainersConfiguration::class, EmotionPipelineTests.ClockOverride::class)
class EmotionPipelineTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var analyzer: ScriptedEmotionAnalyzer

    @Autowired
    lateinit var runner: AnalysisRunner

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var emotionApi: EmotionApi

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var member: TestMember

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        member = MemberFixture(mockMvc).onboarded()
    }

    @Test
    fun `US1-AC4 분석이 끝나면 감정과 강도에 맞는 최대 HP의 몬스터가 HP 가득 찬 상태로 생긴다`() {
        val low = createPost("[불안:낮음] 내일 발표가 걱정돼요")
        val medium = createPost("[짜증:보통] 회의가 또 길어졌다")
        val high = createPost("[외로움:높음] 아무도 내 편이 없다")

        assertThat(awaitMonster(low)).isEqualTo(MonsterView(EmotionType.ANXIETY, 10, 10, MonsterStatus.ALIVE))
        assertThat(awaitMonster(medium)).isEqualTo(MonsterView(EmotionType.IRRITATION, 20, 20, MonsterStatus.ALIVE))
        assertThat(awaitMonster(high)).isEqualTo(MonsterView(EmotionType.LONELINESS, 30, 30, MonsterStatus.ALIVE))

        val views = emotionApi.findByPostIds(listOf(low, medium, high))
        assertThat(views.values.map { it.status }).containsOnly(AnalysisStatus.ANALYZED)
        assertThat(analysisRow(low)["attempts"]).isEqualTo(1)
        assertThat(analysisRow(low)["completed_at"]).isNotNull()
        // 스케줄러 없이 글 작성 직후 리스너가 바로 첫 시도를 했다
        assertThat(analyzer.callsFor(low)).isEqualTo(1)
    }

    @Test
    fun `US1-AC5 분석기가 실패해도 글은 저장되고 회복 후 재시도로 몬스터가 생긴다`() {
        val createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val postId = createPost("[실패:2] 오늘은 아무것도 하기 싫다")
        assertThat(jdbcTemplate.queryForObject("select count(*) from posts where id = ?", Int::class.java, postId))
            .isEqualTo(1)

        // 첫 시도(리스너) 실패: 30초 뒤로 미뤄진다
        awaitAttempts(postId, 1)
        assertThat(analysisRow(postId)["status"]).isEqualTo("PENDING")
        assertThat(analysisRow(postId)["last_error"]).isEqualTo("UPSTREAM_ERROR")
        assertThat(nextAttemptAt(postId)).isEqualTo(createdAt.plusSeconds(30))
        assertThat(emotionApi.findByPostIds(listOf(postId))[postId]?.status).isEqualTo(AnalysisStatus.PENDING)
        assertThat(monsterApi.findByPostIds(listOf(postId))).isEmpty()

        // 아직 기한 전이면 재시도하지 않는다
        clock.advance(Duration.ofSeconds(29))
        runner.runDue()
        assertThat(analyzer.callsFor(postId)).isEqualTo(1)

        // 두 번째 시도도 실패: 60초 뒤로 미뤄진다
        clock.advance(Duration.ofSeconds(1))
        runner.runDue()
        assertThat(analyzer.callsFor(postId)).isEqualTo(2)
        assertThat(analysisRow(postId)["attempts"]).isEqualTo(2)
        assertThat(nextAttemptAt(postId)).isEqualTo(createdAt.plusSeconds(30 + 60))

        // 회복: 세 번째 시도에서 분석되고 몬스터가 생긴다
        clock.advance(Duration.ofSeconds(60))
        runner.runDue()
        assertThat(analyzer.callsFor(postId)).isEqualTo(3)
        assertThat(analysisRow(postId)["status"]).isEqualTo("ANALYZED")
        val monster = awaitMonster(postId)
        assertThat(monster.hp).isEqualTo(monster.maxHp)
        assertThat(monster.status).isEqualTo(MonsterStatus.ALIVE)
    }

    @Test
    fun `US1-AC6 24시간 동안 실패하면 무기력 HP 10 기본 몬스터가 생긴다`() {
        val createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val postId = createPost("[실패] 계속 실패하는 글")
        awaitAttempts(postId, 1)

        // 23시간 59분 59초: 아직 재시도하고 실패한다
        clock.advance(Duration.ofHours(24).minusSeconds(1))
        runner.runDue()
        assertThat(analyzer.callsFor(postId)).isEqualTo(2)
        assertThat(analysisRow(postId)["status"]).isEqualTo("PENDING")
        assertThat(monsterApi.findByPostIds(listOf(postId))).isEmpty()

        // 24시간 정각: 분석기를 부르지 않고 기본값으로 끝낸다
        clock.advance(Duration.ofSeconds(1))
        assertThat(clock.instant()).isAfterOrEqualTo(createdAt.plus(Duration.ofHours(24)))
        runner.runDue()
        assertThat(analyzer.callsFor(postId)).isEqualTo(2)
        val row = analysisRow(postId)
        assertThat(row["status"]).isEqualTo("DEFAULTED")
        assertThat(row["emotion"]).isEqualTo("LETHARGY")
        assertThat(row["intensity"]).isEqualTo("LOW")
        assertThat(awaitMonster(postId)).isEqualTo(MonsterView(EmotionType.LETHARGY, 10, 10, MonsterStatus.ALIVE))
        assertThat(emotionApi.findByPostIds(listOf(postId))[postId]?.status).isEqualTo(AnalysisStatus.DEFAULTED)
    }

    @Test
    fun `스케줄러가 SKIP LOCKED로 다른 실행기가 잡은 행을 건너뛰고 기다리지 않는다`() {
        val postIds = (1..3).map { insertPendingPost("잠긴 행 $it") }
        val other = Executors.newSingleThreadExecutor()
        try {
            transactionTemplate.executeWithoutResult {
                // 이 트랜잭션이 다른 실행기처럼 행을 잡고 있다
                jdbcTemplate.queryForList(
                    "select post_id from emotion_analysis where post_id in (${postIds.joinToString()}) for update",
                )
                other.submit { runner.runDue() }.get(10, TimeUnit.SECONDS)

                assertThat(postIds.map { analyzer.callsFor(it) }).containsOnly(0)
            }
        } finally {
            other.shutdownNow()
        }

        runner.runDue()

        assertThat(postIds.map { analyzer.callsFor(it) }).containsOnly(1)
        assertThat(postIds.map { analysisRow(it)["status"] }).containsOnly("ANALYZED")
    }

    @Test
    fun `두 실행기가 동시에 돌아도 같은 행을 두 번 분석하지 않는다`() {
        val postIds = (1..12).map { insertPendingPost("동시 실행 $it") }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runs =
                (1..2).map {
                    pool.submit {
                        start.await()
                        runner.runDue()
                    }
                }
            start.countDown()
            runs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(postIds.map { analyzer.callsFor(it) }).containsOnly(1)
        assertThat(postIds.map { analysisRow(it)["status"] }).containsOnly("ANALYZED")
        assertThat(postIds.map { analysisRow(it)["attempts"] }).containsOnly(1)
        postIds.forEach { awaitMonster(it) }
    }

    @Test
    fun `분석기가 목록에 없는 감정이나 잘못된 JSON을 주면 실패로 보고 재시도한다`() {
        val unknownEmotion = insertPendingPost("목록에 없는 감정")
        val brokenJson = insertPendingPost("잘못된 JSON")
        analyzer.respondRaw(unknownEmotion, """{"emotion":"HAPPY","intensity":"LOW","reason":"기쁨"}""")
        analyzer.respondRaw(brokenJson, """{"emotion":"ANXIETY",""")

        runner.runDue()

        listOf(unknownEmotion, brokenJson).forEach { postId ->
            val row = analysisRow(postId)
            assertThat(row["status"]).isEqualTo("PENDING")
            assertThat(row["attempts"]).isEqualTo(1)
            assertThat(row["last_error"]).isEqualTo("INVALID_RESPONSE")
            assertThat(row["emotion"]).isNull()
        }
        assertThat(monsterApi.findByPostIds(listOf(unknownEmotion, brokenJson))).isEmpty()

        analyzer.clearRaw(unknownEmotion)
        analyzer.clearRaw(brokenJson)
        clock.advance(Duration.ofSeconds(30))
        runner.runDue()

        assertThat(analysisRow(unknownEmotion)["status"]).isEqualTo("ANALYZED")
        assertThat(analysisRow(brokenJson)["status"]).isEqualTo("ANALYZED")
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

    /** 리스너를 거치지 않고, 지금 시도할 차례인 PENDING 분석 행과 글을 직접 만든다. */
    private fun insertPendingPost(content: String): Long {
        val now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS))
        val postId =
            jdbcTemplate.queryForObject(
                """
                insert into posts (author_id, author_job_role, author_career_year, content, comment_tone,
                                   created_at, updated_at)
                values (?, 'DEVELOPMENT', 'YEAR_3', ?, 'COMFORT_ME', ?, ?)
                returning id
                """.trimIndent(),
                Long::class.java,
                member.id,
                content,
                now,
                now,
            )!!
        jdbcTemplate.update(
            """
            insert into emotion_analysis (post_id, status, attempts, next_attempt_at, post_created_at)
            values (?, 'PENDING', 0, ?, ?)
            """.trimIndent(),
            postId,
            now,
            now,
        )
        return postId
    }

    private fun analysisRow(postId: Long): Map<String, Any?> =
        jdbcTemplate.queryForMap("select * from emotion_analysis where post_id = ?", postId)

    private fun nextAttemptAt(postId: Long): Instant = (analysisRow(postId)["next_attempt_at"] as Timestamp).toInstant()

    private fun awaitAttempts(
        postId: Long,
        attempts: Int,
    ) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).untilAsserted {
            val rows = jdbcTemplate.queryForList("select attempts from emotion_analysis where post_id = ?", postId)
            assertThat(rows.map { it["attempts"] }).containsExactly(attempts)
        }
    }

    private fun awaitMonster(postId: Long): MonsterView {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { monsterApi.findByPostIds(listOf(postId)).isNotEmpty() }
        return monsterApi.findByPostIds(listOf(postId)).getValue(postId)
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
