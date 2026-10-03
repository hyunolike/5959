package com.ogu.feed.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.support.MemberFixture
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * T019: 글 상세(FR-012, FR-015, US1-AC3). 분석 중이면 monster는 null이고, 분석이 끝나면 몬스터가 붙는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PostDetailApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
    }

    @Test
    fun `US1-AC3 분석 중이면 analysisStatus=PENDING이고 monster=null이다`() {
        val author = members.onboarded(jobRole = "MARKETING", careerYear = "YEAR_2")
        // 가짜 분석기는 [실패] 글을 항상 실패시키므로 24시간 동안 분석 중에 머문다
        val postId = createPost(author, "[실패] 분석 중인 글", "VENT_WITH_ME")

        val nickname =
            jdbcTemplate.queryForObject("select nickname from member where id = ?", String::class.java, author.id)
        detail(author, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.postId").value(postId))
            .andExpect(jsonPath("$.data.author.id").value(author.id))
            .andExpect(jsonPath("$.data.author.nickname").value(nickname))
            .andExpect(jsonPath("$.data.author.jobRole").value("MARKETING"))
            .andExpect(jsonPath("$.data.author.careerYear").value("YEAR_2"))
            .andExpect(jsonPath("$.data.content").value("[실패] 분석 중인 글"))
            .andExpect(jsonPath("$.data.commentTone").value("VENT_WITH_ME"))
            .andExpect(jsonPath("$.data.analysisStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.monster").value(null as Any?))
            .andExpect(jsonPath("$.data.likeCount").value(0))
            .andExpect(jsonPath("$.data.likedByMe").value(false))
            .andExpect(jsonPath("$.data.commentCount").value(0))
            .andExpect(jsonPath("$.data.mine").value(true))
            .andExpect(jsonPath("$.data.myCommentCounted").value(false))
            .andExpect(jsonPath("$.data.createdAt").isString)
    }

    @Test
    fun `분석이 끝나면 ANALYZED와 몬스터가 보인다`() {
        val author = members.onboarded()
        val postId = createPost(author, "[불안:보통] 이직 면접이 걱정된다", "WARM_ADVICE")

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until {
            data(detail(author, postId)).get("analysisStatus").asString() == "ANALYZED" &&
                !data(detail(author, postId)).get("monster").isNull
        }

        detail(author, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.analysisStatus").value("ANALYZED"))
            .andExpect(jsonPath("$.data.monster.emotion").value("ANXIETY"))
            .andExpect(jsonPath("$.data.monster.hp").value(20))
            .andExpect(jsonPath("$.data.monster.maxHp").value(20))
            .andExpect(jsonPath("$.data.monster.status").value("ALIVE"))
    }

    @Test
    fun `다른 회원이 보면 mine=false이고, 공감했으면 likedByMe=true다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val postId = createPost(author, "[실패] 남의 글", "COMFORT_ME")

        detail(viewer, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.mine").value(false))
            .andExpect(jsonPath("$.data.likedByMe").value(false))

        jdbcTemplate.update(
            "insert into post_likes (post_id, member_id, created_at) values (?, ?, now())",
            postId,
            viewer.id,
        )

        detail(viewer, postId).andExpect(jsonPath("$.data.likedByMe").value(true))
        detail(author, postId).andExpect(jsonPath("$.data.likedByMe").value(false))
    }

    @Test
    fun `작성 시각은 저장된 시각과 같다`() {
        val author = members.onboarded()
        val postId = createPost(author, "[실패] 시각 확인", "COMFORT_ME")

        val createdAt = Instant.parse(data(detail(author, postId)).get("createdAt").asString())

        val stored =
            jdbcTemplate.queryForObject("select created_at from posts where id = ?", Timestamp::class.java, postId)
        assertThat(createdAt).isEqualTo(stored!!.toInstant())
    }

    @Test
    fun `삭제된 글과 없는 글은 404 POST_NOT_FOUND`() {
        val author = members.onboarded()
        val postId = createPost(author, "[실패] 지울 글", "COMFORT_ME")
        jdbcTemplate.update("update posts set deleted_at = now() where id = ?", postId)

        detail(author, postId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        detail(author, Long.MAX_VALUE)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
    }

    @Test
    fun `글 ID가 숫자가 아니면 500이 아니라 400 INVALID_REQUEST`() {
        val viewer = members.onboarded()

        mockMvc
            .perform(get("/api/v1/posts/{postId}", "abc").bearer(viewer.accessToken))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `온보딩 전 회원은 403, 토큰이 없으면 401`() {
        val author = members.onboarded()
        val postId = createPost(author, "[실패] 보호된 글", "COMFORT_ME")

        detail(members.signedUp(), postId)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        mockMvc.perform(get("/api/v1/posts/{postId}", postId)).andExpect(status().isUnauthorized)
    }

    private fun createPost(
        member: TestMember,
        content: String,
        commentTone: String,
    ): Long {
        val body = mapOf("content" to content, "commentTone" to commentTone)
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

    private fun detail(
        member: TestMember,
        postId: Long,
    ): ResultActions = mockMvc.perform(get("/api/v1/posts/{postId}", postId).bearer(member.accessToken))

    private fun data(result: ResultActions): JsonNode {
        val body = result.andReturn().response.contentAsString
        return jsonMapper.readTree(body).get("data")
    }
}
