package com.ogu.feed.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.feed.application.EmotionStatsQuery
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.QueryCounter
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * T054: 감정 통계 API(004 US4, research R12). 통계는 그 회원의 글만 세므로 테스트마다 새 회원을 만든다. 반올림과
 * 주 경계의 세부는 [com.ogu.feed.application.EmotionStatsQueryTest]가 고정한 시계로 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class EmotionStatsApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var emotionStatsQuery: EmotionStatsQuery

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
    }

    @Test
    fun `US4-AC1 전체와 처치된 몬스터 수, 감정 5종 수와 비율`() {
        val me = members.onboarded()
        val anxiety = post(me, "불안")
        post(me, "불안")
        post(me, "짜증")
        val deleted = post(me, "외로움")
        coreLoop.deletePost(deleted)
        // 분석 중인 글과 남의 글은 세지 않는다
        coreLoop.postWithoutMonster(me)
        post(members.onboarded(), "무기력")
        defeat(anxiety)

        val stats = stats(me)

        assertThat(stats.get("totalMonsters").asInt()).isEqualTo(3)
        assertThat(stats.get("defeatedMonsters").asInt()).isEqualTo(1)
        assertThat(stats.get("defeatedTogether").asInt()).isZero()
        assertThat(stats.get("topEmotion").asString()).isEqualTo("ANXIETY")
        val distribution = stats.get("distribution").values().toList()
        assertThat(distribution.map { it.get("emotion").asString() })
            .containsExactly("ANXIETY", "LETHARGY", "LONELINESS", "SELF_DEPRECATION", "IRRITATION")
        assertThat(distribution.map { it.get("count").asInt() }).containsExactly(2, 0, 0, 0, 1)
        assertThat(distribution.map { it.get("percent").asInt() }).containsExactly(67, 0, 0, 0, 33)
    }

    @Test
    fun `US4-AC3 주별 추이는 글 작성 시각 기준`() {
        val me = members.onboarded()
        val thisWeek = thisMonday()
        val current = post(me, "불안")
        val twoWeeksAgo = post(me, "짜증")
        val tooOld = post(me, "외로움")
        // 몬스터는 방금 생겼지만 글을 쓴 주에 든다(분석이 늦어진 경우와 같다)
        writtenAt(current, thisWeek)
        writtenAt(twoWeeksAgo, thisWeek.minusWeeks(2))
        writtenAt(tooOld, thisWeek.minusWeeks(8))

        val weekly = stats(me).get("weekly").values().toList()

        assertThat(weekly.map { it.get("weekStart").asString() })
            .containsExactlyElementsOf((7 downTo 0).map { thisWeek.minusWeeks(it.toLong()).toString() })
        val counts = weekly.map { week -> week.get("counts").values().map { it.get("count").asInt() } }
        assertThat(counts[7]).containsExactly(1, 0, 0, 0, 0)
        assertThat(counts[5]).containsExactly(0, 0, 0, 0, 1)
        assertThat(counts.flatten().sum()).isEqualTo(2)
        assertThat(weekly[0].get("counts").values().map { it.get("emotion").asString() })
            .containsExactly("ANXIETY", "LETHARGY", "LONELINESS", "SELF_DEPRECATION", "IRRITATION")
    }

    @Test
    fun `US4-AC4 몬스터가 없는 회원은 숫자가 0`() {
        val stats = stats(members.onboarded())

        assertThat(stats.get("totalMonsters").asInt()).isZero()
        assertThat(stats.get("defeatedMonsters").asInt()).isZero()
        assertThat(stats.get("defeatedTogether").asInt()).isZero()
        assertThat(stats.has("topEmotion")).isTrue()
        assertThat(stats.get("topEmotion").isNull).isTrue()
        assertThat(stats.get("distribution").values().map { it.get("percent").asInt() }).containsExactly(0, 0, 0, 0, 0)
        assertThat(stats.get("weekly").size()).isEqualTo(8)
    }

    @Test
    fun `US4-AC5 내가 HP를 줄인 다른 사람의 처치된 몬스터 수가 함께 물리친 몬스터다`() {
        val me = members.onboarded()
        val author = members.onboarded()
        val helpers = (1..2).map { members.onboarded() }
        val latecomer = members.onboarded()
        // HP 10: 한 회원이 공감 1과 첫 댓글 3으로 4를 줄인다. 세 명이면 처치된다
        val defeated = coreLoop.postWithMonster(author)
        val defeatedThenDeleted = coreLoop.postWithMonster(author)
        val alive = coreLoop.postWithMonster(author)
        listOf(defeated, defeatedThenDeleted).forEach { postId ->
            (listOf(me) + helpers).forEach { member ->
                coreLoop.likePost(member, postId).andExpect(status().isOk)
                coreLoop.comment(member, postId)
            }
            assertThat(coreLoop.monster(postId)!!.hp).isZero()
        }
        coreLoop.likePost(me, alive).andExpect(status().isOk)
        // 처치 뒤 응원만 한 회원은 함께 물리친 것이 아니다
        coreLoop.likePost(latecomer, defeated).andExpect(status().isOk)
        coreLoop.deletePost(defeatedThenDeleted)

        // 한 몬스터에 내 기록이 둘(공감, 댓글)이어도 하나로 센다
        assertThat(stats(me).get("defeatedTogether").asInt()).isEqualTo(1)
        assertThat(stats(latecomer).get("defeatedTogether").asInt()).isZero()
        // 글쓴이의 처치된 몬스터는 자기 통계의 처치 수에 들고, 함께 물리친 수에는 들지 않는다
        val authorStats = stats(author)
        assertThat(authorStats.get("totalMonsters").asInt()).isEqualTo(2)
        assertThat(authorStats.get("defeatedMonsters").asInt()).isEqualTo(1)
        assertThat(authorStats.get("defeatedTogether").asInt()).isZero()
    }

    @Test
    fun `쿼리 수는 글 수와 상관없이 4개 이하다`() {
        val few = members.onboarded()
        val many = members.onboarded()
        post(few, "불안")
        (1..6).forEach { post(many, if (it % 2 == 0) "불안" else "짜증") }

        val (fewStats, fewQueries) = QueryCounter.count { emotionStatsQuery.get(few.id) }
        val (manyStats, manyQueries) = QueryCounter.count { emotionStatsQuery.get(many.id) }

        assertThat(fewStats.totalMonsters).isEqualTo(1)
        assertThat(manyStats.totalMonsters).isEqualTo(6)
        assertThat(fewQueries).isEqualTo(manyQueries).isLessThanOrEqualTo(4)
    }

    @Test
    fun `온보딩 전 회원은 403 ONBOARDING_REQUIRED, 토큰이 없으면 401`() {
        mockMvc
            .perform(get(PATH).bearer(members.signedUp().accessToken))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized)
    }

    /** 가짜 분석기 머리말로 감정을 정한 글을 쓰고 몬스터가 생길 때까지 기다린다(최대 HP 10). */
    private fun post(
        author: TestMember,
        emotion: String,
    ): Long {
        val postId = coreLoop.createPost(author, "[$emotion:낮음] 감정 통계에 들어갈 글")
        coreLoop.awaitMonster(postId)
        return postId
    }

    private fun defeat(postId: Long) {
        jdbcTemplate.update(
            "update monsters set hp = 0, status = 'DEFEATED', defeated_at = now() where post_id = ?",
            postId,
        )
    }

    /** 글 작성 시각을 그 주 월요일 0시(한국 시간)로 옮긴다. 이번 주여도 미래가 되지 않는다. */
    private fun writtenAt(
        postId: Long,
        monday: LocalDate,
    ) {
        jdbcTemplate.update(
            "update posts set created_at = ? where id = ?",
            Timestamp.from(monday.atStartOfDay(SEOUL).toInstant()),
            postId,
        )
    }

    private fun thisMonday(): LocalDate =
        Instant
            .now()
            .atZone(SEOUL)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun stats(member: TestMember): JsonNode {
        val body =
            mockMvc
                .perform(get(PATH).bearer(member.accessToken))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return jsonMapper.readTree(body).get("data")
    }

    private companion object {
        const val PATH = "/api/v1/members/me/emotion-stats"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
