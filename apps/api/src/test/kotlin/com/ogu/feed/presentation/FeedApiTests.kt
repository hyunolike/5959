package com.ogu.feed.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.feed.application.FeedQuery
import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.post.PostOrder
import com.ogu.post.PostPageQuery
import com.ogu.support.MemberFixture
import com.ogu.support.QueryCounter
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
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * T029: 피드(US2, FR-011, research R7). 테스트 DB는 다른 테스트 클래스와 같이 쓰므로, 단언은 이 테스트가 만든 글에 대해서만 한다.
 * 새로 만든 글이 가장 최신이므로 최신순 첫 페이지 앞쪽에 온다. 인기순은 다른 글이 닿지 않을 만큼 큰 공감 수를 쓴다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class FeedApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var feedQuery: FeedQuery

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
    }

    @Test
    fun `US2-AC1 최신 20개와 항목 필드`() {
        val author = members.onboarded(jobRole = "MARKETING", careerYear = "YEAR_2")
        val viewer = members.onboarded()
        val older = (1..20).map { insertPost(author, "글 $it") }
        // 가장 최신 글은 실제 API로 써서 감정 분석과 몬스터까지 붙인다
        val newest = createPost(author, "[불안:보통] 이직 면접이 걱정된다")
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until {
            hasMonster(newest)
        }
        jdbcTemplate.update("update posts set like_count = 3, comment_count = 2 where id = ?", newest)
        like(newest, viewer)

        val page = data(feed(viewer))

        val ids = page.get("items").values().map { it.get("postId").asLong() }
        assertThat(ids).containsExactlyElementsOf(listOf(newest) + older.reversed().take(19))
        assertThat(page.get("nextCursor").isString).isTrue()

        val nickname =
            jdbcTemplate.queryForObject("select nickname from member where id = ?", String::class.java, author.id)
        val first = page.get("items").get(0)
        assertThat(first.get("author").get("id").asLong()).isEqualTo(author.id)
        assertThat(first.get("author").get("nickname").asString()).isEqualTo(nickname)
        assertThat(first.get("author").get("jobRole").asString()).isEqualTo("MARKETING")
        assertThat(first.get("author").get("careerYear").asString()).isEqualTo("YEAR_2")
        assertThat(first.get("contentPreview").asString()).isEqualTo("[불안:보통] 이직 면접이 걱정된다")
        assertThat(first.get("analysisStatus").asString()).isEqualTo("ANALYZED")
        assertThat(first.get("monster").get("emotion").asString()).isEqualTo("ANXIETY")
        assertThat(first.get("monster").get("hp").asInt()).isEqualTo(20)
        assertThat(first.get("monster").get("maxHp").asInt()).isEqualTo(20)
        assertThat(first.get("monster").get("status").asString()).isEqualTo("ALIVE")
        assertThat(first.get("likeCount").asInt()).isEqualTo(3)
        assertThat(first.get("likedByMe").asBoolean()).isTrue()
        assertThat(first.get("commentCount").asInt()).isEqualTo(2)
        val stored =
            jdbcTemplate.queryForObject("select created_at from posts where id = ?", Timestamp::class.java, newest)
        assertThat(Instant.parse(first.get("createdAt").asString())).isEqualTo(stored!!.toInstant())
        assertThat(
            page
                .get("items")
                .get(1)
                .get("likedByMe")
                .asBoolean(),
        ).isFalse()
    }

    @Test
    fun `US2-AC2 커서로 다음 20개, 중복과 누락 없음`() {
        val author = members.onboarded()
        val mine = (1..25).map { insertPost(author, "이어 보기 $it") }.reversed()

        val first = data(feed(author))
        val firstIds = ids(first)
        assertThat(firstIds).containsExactlyElementsOf(mine.take(20))

        // 페이지 사이에 새 글이 올라와도 다음 페이지가 밀리지 않는다
        val inserted = insertPost(author, "사이에 끼어든 글")
        val second = data(feed(author, "cursor" to first.get("nextCursor").asString()))
        val secondIds = ids(second)

        assertThat(secondIds.take(5)).containsExactlyElementsOf(mine.drop(20))
        assertThat(secondIds).doesNotContain(inserted).doesNotContainAnyElementsOf(firstIds)
        assertThat(secondIds).allSatisfy { assertThat(it).isLessThan(firstIds.last()) }
    }

    @Test
    fun `끝까지 넘기면 nextCursor는 null이고 같은 글이 두 번 나오지 않는다`() {
        val viewer = members.onboarded()
        insertPost(viewer, "끝까지 넘기기")
        val seen = mutableListOf<Long>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val params = listOfNotNull("size" to "50", cursor?.let { c -> "cursor" to c })
            val page = data(feed(viewer, *params.toTypedArray()))
            seen += ids(page)
            val next = page.get("nextCursor")
            if (next.isNull) {
                assertThat(seen).doesNotHaveDuplicates()
                val alive = jdbcTemplate.queryForObject(ALIVE_POSTS, Int::class.java)
                assertThat(seen).hasSizeGreaterThanOrEqualTo(alive!!)
                return
            }
            cursor = next.asString()
        }
        error("${MAX_PAGES}쪽 안에 끝나지 않았다")
    }

    @Test
    fun `US2-AC3 인기순은 공감 수 내림차순, 같으면 최신`() {
        val viewer = members.onboarded()
        val author = members.onboarded()
        val low = insertPost(author, "공감 적음", likeCount = POPULAR_BASE + 1)
        val tieOld = insertPost(author, "공감 같음 1", likeCount = POPULAR_BASE + 3)
        val top = insertPost(author, "공감 많음", likeCount = POPULAR_BASE + 5)
        val tieMid = insertPost(author, "공감 같음 2", likeCount = POPULAR_BASE + 3)
        val tieNew = insertPost(author, "공감 같음 3", likeCount = POPULAR_BASE + 3)

        // 크기 2로 넘기면 같은 공감 수 셋이 페이지 경계에 걸친다
        val collected = mutableListOf<Long>()
        var cursor: String? = null
        repeat(3) {
            val params = listOfNotNull("order" to "POPULAR", "size" to "2", cursor?.let { c -> "cursor" to c })
            val page = data(feed(viewer, *params.toTypedArray()))
            collected += ids(page)
            cursor = page.get("nextCursor").asString()
        }

        assertThat(collected.take(5)).containsExactly(top, tieNew, tieMid, tieOld, low)
        assertThat(collected).doesNotHaveDuplicates()
    }

    @Test
    fun `US2-AC4 직군 여러 개 OR, 경력 여러 개 OR, 둘은 AND`() {
        val viewer = members.onboarded()
        val devYear1 = insertPost(members.onboarded("DEVELOPMENT", "YEAR_1"), "개발 1년차")
        val designYear3 = insertPost(members.onboarded("DESIGN", "YEAR_3"), "디자인 3년차")
        val devYear5 = insertPost(members.onboarded("DEVELOPMENT", "YEAR_5"), "개발 5년차")
        val marketingYear1 = insertPost(members.onboarded("MARKETING", "YEAR_1"), "마케팅 1년차")

        val both =
            data(
                feed(
                    viewer,
                    "jobRole" to "DEVELOPMENT",
                    "jobRole" to "DESIGN",
                    "careerYear" to "YEAR_1",
                    "careerYear" to "YEAR_3",
                    "size" to "50",
                ),
            )
        assertThat(ids(both)).contains(devYear1, designYear3).doesNotContain(devYear5, marketingYear1)
        both.get("items").forEach {
            assertThat(it.get("author").get("jobRole").asString()).isIn("DEVELOPMENT", "DESIGN")
            assertThat(it.get("author").get("careerYear").asString()).isIn("YEAR_1", "YEAR_3")
        }

        val jobOnly = data(feed(viewer, "jobRole" to "DEVELOPMENT", "size" to "50"))
        assertThat(ids(jobOnly)).contains(devYear1, devYear5).doesNotContain(designYear3, marketingYear1)

        val careerOnly = data(feed(viewer, "careerYear" to "YEAR_1", "size" to "50"))
        assertThat(ids(careerOnly)).contains(devYear1, marketingYear1).doesNotContain(designYear3, devYear5)
    }

    @Test
    fun `US2-AC5 삭제된 글 제외`() {
        val author = members.onboarded()
        val kept = insertPost(author, "남는 글")
        val deleted = insertPost(author, "지운 글")
        jdbcTemplate.update("update posts set deleted_at = now() where id = ?", deleted)

        val ids = ids(data(feed(author)))

        assertThat(ids).contains(kept).doesNotContain(deleted)
        val popular = ids(data(feed(author, "order" to "POPULAR", "size" to "50")))
        assertThat(popular).doesNotContain(deleted)
    }

    @Test
    fun `본문 미리보기는 앞 50자이고 더 길면 끝에 말줄임표를 붙인다`() {
        val author = members.onboarded()
        val exact = "가".repeat(50)
        // 결합 이모지도 한 글자로 센다(research R8)
        val long = "👨‍👩‍👧" + "나".repeat(59)
        val exactId = insertPost(author, exact)
        val longId = insertPost(author, long)

        val items = data(feed(author)).get("items").associateBy { it.get("postId").asLong() }

        assertThat(items.getValue(exactId).get("contentPreview").asString()).isEqualTo(exact)
        assertThat(items.getValue(longId).get("contentPreview").asString())
            .isEqualTo("👨‍👩‍👧" + "나".repeat(49) + "...")
    }

    @Test
    fun `분석 중 글은 analysisStatus=PENDING이고 monster=null`() {
        val author = members.onboarded()
        val pending = insertPost(author, "아직 분석 전")

        val item = data(feed(author)).get("items").first { it.get("postId").asLong() == pending }

        assertThat(item.get("analysisStatus").asString()).isEqualTo("PENDING")
        assertThat(item.has("monster")).isTrue()
        assertThat(item.get("monster").isNull).isTrue()
    }

    @Test
    fun `피드 한 쪽은 크기와 상관없이 쿼리 4개다`() {
        // 작성자 여럿, 분석된 글과 분석 중 글, 내가 공감한 글이 섞인 페이지
        val authors = (1..3).map { members.onboarded("DEVELOPMENT", "YEAR_3") }
        val viewer = members.onboarded()
        val posts = (1..21).map { insertPost(authors[it % authors.size], "쿼리 수 $it") }
        like(posts.first(), viewer)
        val analyzed = createPost(authors.first(), "[짜증:낮음] 회의가 너무 길다")
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until {
            hasMonster(analyzed)
        }

        val (small, smallQueries) =
            QueryCounter.count { feedQuery.get(PostPageQuery(viewerId = viewer.id, size = 5)) }
        val (large, largeQueries) =
            QueryCounter.count {
                feedQuery.get(
                    PostPageQuery(
                        viewerId = viewer.id,
                        order = PostOrder.POPULAR,
                        jobRoles = setOf(JobRole.DEVELOPMENT),
                        careerYears = setOf(CareerYear.YEAR_3),
                        size = 20,
                    ),
                )
            }

        assertThat(small.items).hasSize(5)
        assertThat(large.items).hasSize(20)
        assertThat(smallQueries).isEqualTo(4)
        assertThat(largeQueries).isEqualTo(4)
    }

    @Test
    fun `size는 1부터 50까지, 벗어나면 400 INVALID_REQUEST`() {
        val viewer = members.onboarded()
        insertPost(viewer, "크기 확인")

        feed(viewer, "size" to "1").andExpect(status().isOk).andExpect(jsonPath("$.data.items.length()").value(1))
        feed(viewer, "size" to "50").andExpect(status().isOk)
        listOf("0", "51", "-1", "abc").forEach { size ->
            feed(viewer, "size" to size)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `잘못된 커서, 정렬, 필터 값은 400 INVALID_REQUEST`() {
        val viewer = members.onboarded()
        val badCursors =
            listOf(
                "!!!",
                base64Url("abc"),
                base64Url("1"),
                base64Url("1:2:3"),
                base64Url("-1:5"),
                base64Url("x:5"),
                base64Url("3:0"),
            )
        badCursors.forEach { cursor ->
            feed(viewer, "cursor" to cursor)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
        listOf("order" to "OLDEST", "jobRole" to "ASTRONAUT", "careerYear" to "YEAR_99").forEach { param ->
            feed(viewer, param)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `직군이나 경력에 빈 값이 섞이면 500이 아니라 400 INVALID_REQUEST`() {
        val viewer = members.onboarded()
        val badFilters =
            listOf(
                arrayOf("jobRole" to "", "jobRole" to "HR"),
                arrayOf("jobRole" to ",HR"),
                arrayOf("careerYear" to "", "careerYear" to "YEAR_1"),
                arrayOf("careerYear" to ",YEAR_1"),
            )
        badFilters.forEach { params ->
            feed(viewer, *params)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `인기순에서 쪽 사이에 공감 수가 바뀌면 커서 위로 올라간 글은 빠지고 아래로 내려간 글은 다시 나온다`() {
        // 키셋 페이지네이션이 받아들인 한계(research R7). 웹은 같은 글을 한 번만 보여 준다(feed-list).
        // 이 테스트만 쓰는 직군과 경력 조합으로 거르고, US2-AC3의 공감 수보다 작은 값을 쓴다.
        val viewer = members.onboarded()
        val author = members.onboarded("OTHER", "YEAR_7_PLUS")
        val a = insertPost(author, "인기 A", likeCount = SHIFT_BASE + 5)
        val b = insertPost(author, "인기 B", likeCount = SHIFT_BASE + 4)
        val c = insertPost(author, "인기 C", likeCount = SHIFT_BASE + 3)
        val d = insertPost(author, "인기 D", likeCount = SHIFT_BASE + 2)
        val filter = arrayOf("order" to "POPULAR", "jobRole" to "OTHER", "careerYear" to "YEAR_7_PLUS", "size" to "2")

        val first = data(feed(viewer, *filter))
        assertThat(ids(first)).containsExactly(a, b)

        // 첫 쪽을 본 뒤 D는 커서(B) 위로 올라가고, A는 커서 아래로 내려간다
        jdbcTemplate.update("update posts set like_count = ? where id = ?", SHIFT_BASE + 10, d)
        jdbcTemplate.update("update posts set like_count = ? where id = ?", SHIFT_BASE + 1, a)
        val second = data(feed(viewer, *filter, "cursor" to first.get("nextCursor").asString()))

        assertThat(ids(second)).containsExactly(c, a)
        assertThat(ids(second)).doesNotContain(d)
    }

    @Test
    fun `온보딩 전 회원은 403, 토큰이 없으면 401`() {
        feed(members.signedUp())
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        mockMvc.perform(get("/api/v1/feed")).andExpect(status().isUnauthorized)
    }

    /**
     * 감정 분석 없이 글을 바로 넣는다(분석 중으로 남는다). 작성 시각을 2시간 전으로 두어 작성 제한(1시간 10개)에 세지 않게
     * 한다. 피드 순서는 작성 시각이 아니라 글 ID로 정한다.
     */
    private fun insertPost(
        author: TestMember,
        content: String,
        likeCount: Int = 0,
    ): Long {
        val profile =
            jdbcTemplate.queryForMap("select job_role, career_year from member where id = ?", author.id)
        val now = Timestamp.from(Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MICROS))
        return jdbcTemplate.queryForObject(
            """
            insert into posts (author_id, author_job_role, author_career_year, content, comment_tone, like_count,
                               created_at, updated_at)
            values (?, ?, ?, ?, 'COMFORT_ME', ?, ?, ?)
            returning id
            """.trimIndent(),
            Long::class.java,
            author.id,
            profile["job_role"],
            profile["career_year"],
            content,
            likeCount,
            now,
            now,
        )!!
    }

    private fun createPost(
        member: TestMember,
        content: String,
    ): Long {
        val body = mapOf("content" to content, "commentTone" to "WARM_ADVICE")
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

    private fun like(
        postId: Long,
        member: TestMember,
    ) {
        jdbcTemplate.update(
            "insert into post_likes (post_id, member_id, created_at) values (?, ?, now())",
            postId,
            member.id,
        )
    }

    private fun feed(
        member: TestMember,
        vararg params: Pair<String, String>,
    ): ResultActions {
        val request = get("/api/v1/feed").bearer(member.accessToken)
        params.forEach { (name, value) -> request.queryParam(name, value) }
        return mockMvc.perform(request)
    }

    private fun data(result: ResultActions): JsonNode {
        val body =
            result
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return jsonMapper.readTree(body).get("data")
    }

    private fun hasMonster(postId: Long): Boolean =
        jdbcTemplate.queryForObject("select count(*) from monsters where post_id = ?", Int::class.java, postId) == 1

    private fun ids(page: JsonNode): List<Long> = page.get("items").values().map { it.get("postId").asLong() }

    private fun base64Url(raw: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val MAX_PAGES = 200
        const val POPULAR_BASE = 1_000_000
        const val SHIFT_BASE = 500_000
        const val ALIVE_POSTS = "select count(*) from posts where deleted_at is null"
    }
}
