package com.ogu.post.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.post.PostCreated
import com.ogu.support.MemberFixture
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * T015: 고민 글 작성 (US1-AC1, US1-AC2, US1-AC7, FR-001, FR-018, research R7, R9).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@EnableScenarios
class PostCreateApiTests {
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
    fun `US1-AC1 1~500자 본문과 말투로 작성하면 201과 PENDING`() {
        val member = members.onboarded()

        val postId =
            createPost(member, "  내일 발표가 걱정돼요  ", "COMFORT_ME")
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.postId").isNumber)
                .andExpect(jsonPath("$.data.analysisStatus").value("PENDING"))
                .postId()

        val row = jdbcTemplate.queryForMap("select * from posts where id = ?", postId)
        assertThat(row["author_id"]).isEqualTo(member.id)
        assertThat(row["content"]).isEqualTo("내일 발표가 걱정돼요")
        assertThat(row["comment_tone"]).isEqualTo("COMFORT_ME")
        assertThat(row["like_count"]).isEqualTo(0)
        assertThat(row["comment_count"]).isEqualTo(0)
        assertThat(row["deleted_at"]).isNull()

        createPost(member, "가", "MAKE_ME_LAUGH").andExpect(status().isCreated)
    }

    @ParameterizedTest
    @MethodSource("invalidContents")
    fun `US1-AC2 빈 본문, 공백만, 501자, 말투 없음은 400`(content: String) {
        val member = members.onboarded()

        createPost(member, content, "COMFORT_ME")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))

        assertThat(postCount(member)).isZero()
    }

    @Test
    fun `US1-AC2 500자 경계는 사람이 보는 글자로 센다(이모지와 결합 이모지는 1자)`() {
        val member = members.onboarded()

        createPost(member, "👍".repeat(500), "VENT_WITH_ME").andExpect(status().isCreated)
        createPost(member, "가".repeat(400) + "👨‍👩‍👧".repeat(100), "VENT_WITH_ME").andExpect(status().isCreated)
        createPost(member, "  " + "가".repeat(500) + "  ", "VENT_WITH_ME").andExpect(status().isCreated)
        createPost(member, "👍".repeat(501), "VENT_WITH_ME").andExpect(status().isBadRequest)
        createPost(member, "가".repeat(401) + "👨‍👩‍👧".repeat(100), "VENT_WITH_ME").andExpect(status().isBadRequest)

        assertThat(postCount(member)).isEqualTo(3)
    }

    @Test
    fun `US1-AC2 결합 이모지 500자(코드 포인트 2500개)도 저장된다`() {
        val member = members.onboarded()
        val content = "👨‍👩‍👧".repeat(500)

        val postId = createPost(member, content, "VENT_WITH_ME").andExpect(status().isCreated).postId()

        val stored = jdbcTemplate.queryForObject("select content from posts where id = ?", String::class.java, postId)
        assertThat(stored).isEqualTo(content)
    }

    @Test
    fun `결합 문자를 겹쳐 코드 포인트가 5000개를 넘는 글(Zalgo)은 글자 수가 적어도 400이다`() {
        val member = members.onboarded()
        // 글자 하나에 결합 문자 999개: 5자이지만 코드 포인트는 5000개다
        val zalgoChar = "a" + "\u0301".repeat(999)
        val atCap = zalgoChar.repeat(5)
        val overCap = atCap + "\u0301"

        createPost(member, atCap, "VENT_WITH_ME").andExpect(status().isCreated)
        createPost(member, overCap, "VENT_WITH_ME")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))

        assertThat(postCount(member)).isEqualTo(1)
    }

    @Test
    fun `US1-AC2 말투가 없거나 목록에 없거나 본문이 없으면 400`() {
        val member = members.onboarded()
        val bodies =
            listOf(
                mapOf("content" to "고민"),
                mapOf("content" to "고민", "commentTone" to null),
                mapOf("content" to "고민", "commentTone" to "ANGRY"),
                mapOf("commentTone" to "COMFORT_ME"),
                mapOf("content" to null, "commentTone" to "COMFORT_ME"),
            )

        bodies.forEach { body ->
            createPostRaw(member, jsonMapper.writeValueAsString(body))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
        assertThat(postCount(member)).isZero()
    }

    @Test
    fun `US1-AC7 온보딩 전 회원은 403 ONBOARDING_REQUIRED`() {
        val member = members.signedUp()

        createPost(member, "고민", "COMFORT_ME")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        mockMvc
            .perform(
                post("/api/v1/posts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"고민","commentTone":"COMFORT_ME"}"""),
            ).andExpect(status().isUnauthorized)

        assertThat(postCount(member)).isZero()
    }

    @Test
    fun `작성자 직군과 경력이 글에 스냅숏으로 저장된다`() {
        val member = members.onboarded(jobRole = "DESIGN", careerYear = "YEAR_7_PLUS")

        val postId = createPost(member, "디자인 리뷰가 무섭다", "WARM_ADVICE").andExpect(status().isCreated).postId()

        val row = jdbcTemplate.queryForMap("select author_job_role, author_career_year from posts where id = ?", postId)
        assertThat(row["author_job_role"]).isEqualTo("DESIGN")
        assertThat(row["author_career_year"]).isEqualTo("YEAR_7_PLUS")
    }

    @Test
    fun `1시간에 11번째 글은 429 POST_RATE_LIMITED이고 지운 글도 개수에 들어간다`() {
        val member = members.onboarded()
        val postIds = (1..10).map { createPost(member, "고민 $it", "COMFORT_ME").andExpect(status().isCreated).postId() }
        jdbcTemplate.update("update posts set deleted_at = now() where id = ?", postIds.first())

        val retryAfter =
            createPost(member, "열한 번째", "COMFORT_ME")
                .andExpect(status().isTooManyRequests)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("POST_RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn()
                .let {
                    jsonMapper
                        .readTree(it.response.contentAsString)
                        .get("error")
                        .get("retryAfterSeconds")
                        .asInt()
                }
        assertThat(retryAfter).isBetween(1, 3600)
        assertThat(postCount(member)).isEqualTo(10)

        // 가장 오래된 글이 1시간 창을 벗어나면 다시 쓸 수 있다
        jdbcTemplate.update(
            "update posts set created_at = created_at - interval '61 minutes' where id = ?",
            postIds.first(),
        )
        createPost(member, "다시 쓴다", "COMFORT_ME").andExpect(status().isCreated)
    }

    @Test
    fun `글을 쓰면 커밋 후 PostCreated가 발행되어 감정 분석이 예약된다`(scenario: Scenario) {
        val member = members.onboarded()

        scenario
            .stimulate(Runnable { createPost(member, "  발행 확인  ", "WARM_ADVICE").andExpect(status().isCreated) })
            .andWaitForEventOfType(PostCreated::class.java)
            .matching { it.authorId == member.id }
            .toArriveAndVerify { event ->
                val row = jdbcTemplate.queryForMap("select content, created_at from posts where id = ?", event.postId)
                assertThat(row["content"]).isEqualTo("발행 확인")
                assertThat(event.createdAt).isEqualTo((row["created_at"] as java.sql.Timestamp).toInstant())
            }

        val postId = jdbcTemplate.queryForObject(POST_ID_BY_AUTHOR, Long::class.java, member.id)
        // 커밋 뒤 emotion 모듈의 리스너가 분석 행을 만든다
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val count =
                jdbcTemplate.queryForObject(
                    "select count(*) from emotion_analysis where post_id = ?",
                    Int::class.java,
                    postId,
                )
            assertThat(count).isEqualTo(1)
        }
    }

    private fun createPost(
        member: TestMember,
        content: String,
        commentTone: String,
    ): ResultActions {
        val body = mapOf("content" to content, "commentTone" to commentTone)
        return createPostRaw(member, jsonMapper.writeValueAsString(body))
    }

    private fun createPostRaw(
        member: TestMember,
        body: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/posts").bearer(member.accessToken).contentType(MediaType.APPLICATION_JSON).content(body),
        )

    private fun ResultActions.postId(): Long =
        jsonMapper
            .readTree(andReturn().response.contentAsString)
            .get("data")
            .get("postId")
            .asLong()

    private fun postCount(member: TestMember): Int =
        jdbcTemplate.queryForObject("select count(*) from posts where author_id = ?", Int::class.java, member.id)!!

    companion object {
        private const val POST_ID_BY_AUTHOR = "select id from posts where author_id = ?"

        @JvmStatic
        fun invalidContents(): List<String> = listOf("", "   ", "\n\t  ", "가".repeat(501), "👍".repeat(501))
    }
}
