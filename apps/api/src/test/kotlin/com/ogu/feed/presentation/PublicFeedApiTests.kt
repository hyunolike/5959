package com.ogu.feed.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.feed.application.PublicFeedQuery
import com.ogu.monster.MonsterApi
import com.ogu.safety.RiskApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.QueryCounter
import com.ogu.support.SafetyFixture
import com.ogu.support.SafetyFixture.Companion.CONCERN_TEXT
import com.ogu.support.SafetyFixture.Companion.CRISIS_TEXT
import com.ogu.support.SafetyFixture.Companion.SAFE_TEXT
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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

/**
 * 로그인하지 않은 방문자가 보는 최근 글(011 US1, US2, research R1~R4). 목록은 모든 회원의 최근 글이라, 테스트마다
 * 방금 쓴 글이 목록의 앞쪽에 있는지로 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PublicFeedApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var riskApi: RiskApi

    @Autowired
    lateinit var publicFeedQuery: PublicFeedQuery

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var safety: SafetyFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
    }

    @Test
    fun `US1-AC1 로그인 없이 최근 글부터 받는다`() {
        val author = members.onboarded()
        val older = settledPost(author, "[실패] 먼저 쓴 글")
        val newer = settledPost(author, "[실패] 나중에 쓴 글")

        val ids = feed().map { it.get("postId").asLong() }

        assertThat(ids).contains(older, newer)
        assertThat(ids.indexOf(newer)).isLessThan(ids.indexOf(older))
        assertThat(ids).isSortedAccordingTo(reverseOrder())
    }

    @Test
    fun `US1-AC2 감정, 직군과 경력, 본문의 앞부분, 몬스터, 수, 올린 때가 있다`() {
        val author = members.onboarded(jobRole = "DESIGN", careerYear = "YEAR_5")
        val helper = members.onboarded()
        val postId = loop.createPost(author, "[불안:낮음] " + "가".repeat(60))
        loop.awaitMonster(postId)
        awaitSettled(postId)
        loop.likePost(helper, postId).andExpect(status().isOk)
        loop.comment(helper, postId)

        val item = itemOf(postId)!!

        assertThat(item.get("jobRole").asString()).isEqualTo("DESIGN")
        assertThat(item.get("careerYear").asString()).isEqualTo("YEAR_5")
        // 회원의 피드와 같은 길이로 자른다(앞 50글자와 말줄임)
        assertThat(item.get("contentPreview").asString()).hasSize(53).endsWith("...")
        assertThat(item.get("analysisStatus").asString()).isEqualTo("ANALYZED")
        assertThat(item.get("monster").get("emotion").asString()).isEqualTo("ANXIETY")
        assertThat(item.get("monster").get("maxHp").asInt()).isEqualTo(10)
        assertThat(item.get("likeCount").asInt()).isEqualTo(1)
        assertThat(item.get("commentCount").asInt()).isEqualTo(1)
        assertThat(item.get("createdAt").asString()).isNotBlank()
    }

    @Test
    fun `US1-AC2 감정 분석이 끝나지 않은 글은 몬스터 없이 분석 중이다`() {
        val postId = settledPost(members.onboarded(), "[실패] 분석이 끝나지 않는 글")

        val item = itemOf(postId)!!

        assertThat(item.get("analysisStatus").asString()).isEqualTo("PENDING")
        assertThat(item.get("monster").isNull).isTrue()
    }

    @Test
    fun `US1-AC3 응답 원문에 닉네임과 회원 번호와 내 공감 여부가 없다`() {
        val author = members.onboarded()
        val postId = settledPost(author, "[실패] 누가 썼는지는 보이지 않는 글")
        val nickname =
            jdbcTemplate.queryForObject("select nickname from member where id = ?", String::class.java, author.id)!!

        val raw =
            mockMvc
                .perform(get(PUBLIC_FEED))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        assertThat(raw).contains("\"postId\":$postId")
        assertThat(raw).doesNotContain(nickname)
        assertThat(raw).doesNotContain("nickname", "author", "memberId", "likedByMe", "hidden", "nextCursor")
        // 글 하나의 필드는 계약에 적힌 아홉 개뿐이다
        assertThat(itemOf(postId)!!.propertyNames()).containsExactlyInAnyOrder(
            "postId",
            "jobRole",
            "careerYear",
            "contentPreview",
            "analysisStatus",
            "monster",
            "likeCount",
            "commentCount",
            "createdAt",
        )
    }

    @Test
    fun `US1-AC5 스무 개까지만 주고 넘겨 볼 방법이 없다`() {
        val author = members.onboarded()
        val posts = (1..25).map { safety.insertPost(author, "$SAFE_TEXT $it") }

        val data = data()
        val ids = data.get("items").toList().map { it.get("postId").asLong() }

        assertThat(ids).hasSize(20)
        assertThat(ids).containsExactlyElementsOf(posts.sortedDescending().take(20))
        assertThat(data.propertyNames()).containsExactly("items")
        // 커서나 크기를 줘도 같은 스무 개다
        mockMvc
            .perform(get(PUBLIC_FEED).param("size", "50").param("cursor", "x"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(20))
    }

    @Test
    fun `US2-AC1 지운 글과 숨긴 글은 없다`() {
        val author = members.onboarded()
        val deleted = settledPost(author, "[실패] 지울 글")
        val hidden = settledPost(author, "[실패] 숨길 글")
        val kept = settledPost(author, "[실패] 남을 글")
        assertThat(feedIds()).contains(deleted, hidden, kept)

        loop.deletePost(deleted)
        jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'OPERATOR' where id = ?", hidden)

        assertThat(feedIds()).contains(kept).doesNotContain(deleted, hidden)
    }

    @Test
    fun `US2-AC2 우려와 위기로 판정된 글은 없다`() {
        val author = members.onboarded()
        val concernByAi = loop.createPost(author, "[실패] [우려] $SAFE_TEXT")
        val concernByKeyword = loop.createPost(author, "[실패] $CONCERN_TEXT")
        val crisis = loop.createPost(author, "[실패] $CRISIS_TEXT")
        val safe = settledPost(author, "[실패] $SAFE_TEXT")
        listOf(concernByAi, concernByKeyword).forEach(::awaitSettled)

        // 회원에게는 보이는 우려 글도 로그인하지 않은 방문자에게는 내보내지 않는다
        assertThat(feedIds()).contains(safe).doesNotContain(concernByAi, concernByKeyword, crisis)
    }

    @Test
    fun `US2-AC3 위험 판정이 끝나지 않은 글은 없고 끝나면 보인다`() {
        val author = members.onboarded()
        val waiting = loop.createPost(author, "[실패] [위험분류실패] $SAFE_TEXT")
        val settled = settledPost(author, "[실패] $SAFE_TEXT")

        assertThat(feedIds()).contains(settled).doesNotContain(waiting)

        // 판정이 위험 없음으로 끝난 것처럼 만든다
        jdbcTemplate.update(
            "update risk_assessment set status = 'DONE', ai_level = 'NONE', completed_at = now() " +
                "where target_type = 'POST' and target_id = ?",
            waiting,
        )
        assertThat(feedIds()).contains(waiting)
    }

    @Test
    fun `US2-AC4 욕설을 가린다`() {
        val postId = settledPost(members.onboarded(), "[실패] 오늘 팀장이 병신 같은 소리를 해서 화가 났다")

        assertThat(itemOf(postId)!!.get("contentPreview").asString())
            .isEqualTo("[실패] 오늘 팀장이 ** 같은 소리를 해서 화가 났다")
    }

    @Test
    fun `US2-AC5 로그인 없이는 회원의 피드와 글 상세와 댓글이 401이다`() {
        val postId = settledPost(members.onboarded(), "[실패] 상세는 로그인해야 보는 글")

        for (path in listOf("/api/v1/feed", "/api/v1/posts/$postId", "/api/v1/posts/$postId/comments")) {
            mockMvc
                .perform(get(path))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
        }
        // 공개 조회의 이웃 경로도 열리지 않는다
        mockMvc.perform(get("/api/v1/public/posts/$postId")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `SC-004 쿼리는 글 수와 상관없이 네 개다`() {
        val author = members.onboarded()
        settledPost(author, "[실패] 쿼리를 세는 글")

        val (page, queries) = QueryCounter.count { publicFeedQuery.get() }

        assertThat(page.items).isNotEmpty()
        assertThat(queries).isEqualTo(4)
    }

    /** 글을 쓰고 위험 판정이 끝날 때까지 기다린다. */
    private fun settledPost(
        author: TestMember,
        content: String,
    ): Long = loop.createPost(author, content).also(::awaitSettled)

    private fun awaitSettled(postId: Long) {
        await().atMost(SafetyFixture.AWAIT_LIMIT).pollInterval(SafetyFixture.POLL).until {
            jdbcTemplate.queryForObject(
                "select count(*) from risk_assessment where target_type = 'POST' and target_id = ?",
                Int::class.java,
                postId,
            )!! > 0 && riskApi.unsettledPostIds(listOf(postId)).isEmpty()
        }
    }

    private fun data(): JsonNode {
        val body =
            mockMvc
                .perform(get(PUBLIC_FEED))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return jsonMapper.readTree(body).get("data")
    }

    private fun feed(): List<JsonNode> = data().get("items").toList()

    private fun feedIds(): List<Long> = feed().map { it.get("postId").asLong() }

    private fun itemOf(postId: Long): JsonNode? = feed().firstOrNull { it.get("postId").asLong() == postId }

    companion object {
        private const val PUBLIC_FEED = "/api/v1/public/feed"
    }
}
