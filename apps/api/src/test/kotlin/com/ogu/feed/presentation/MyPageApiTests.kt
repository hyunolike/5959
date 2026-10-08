package com.ogu.feed.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.feed.application.MyPageQuery
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
 * T047: 마이페이지의 세 목록(004 US3, research R12). 목록은 그 회원의 활동만 담으므로, 테스트마다 새 회원을 만들어
 * 목록 전체를 정확히 단언한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MyPageApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var myPageQuery: MyPageQuery

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
    fun `US3-AC1 내 글이 최신순 20개씩이고 피드 항목과 같은 필드이며 지운 글은 없다`() {
        val me = members.onboarded(jobRole = "MARKETING", careerYear = "YEAR_2")
        val other = members.onboarded()
        val older = (1..21).map { insertPost(me, "내 글 $it") }
        val deleted = insertPost(me, "지운 글")
        coreLoop.deletePost(deleted)
        val others = insertPost(other, "남의 글")
        // 가장 최신 글은 실제 API로 써서 감정 분석과 몬스터까지 붙인다
        val newest = coreLoop.createPost(me, "[불안:보통] 이직 면접이 걱정된다")
        coreLoop.awaitMonster(newest)
        jdbcTemplate.update("update posts set like_count = 3, comment_count = 2 where id = ?", newest)
        insertLike(newest, me)

        val first = data(list(POSTS, me))

        assertThat(postIds(first)).containsExactlyElementsOf(listOf(newest) + older.reversed().take(19))
        val item = first.get("items").get(0)
        val nickname =
            jdbcTemplate.queryForObject("select nickname from member where id = ?", String::class.java, me.id)
        assertThat(item.get("author").get("id").asLong()).isEqualTo(me.id)
        assertThat(item.get("author").get("nickname").asString()).isEqualTo(nickname)
        assertThat(item.get("author").get("jobRole").asString()).isEqualTo("MARKETING")
        assertThat(item.get("author").get("careerYear").asString()).isEqualTo("YEAR_2")
        assertThat(item.get("contentPreview").asString()).isEqualTo("[불안:보통] 이직 면접이 걱정된다")
        assertThat(item.get("analysisStatus").asString()).isEqualTo("ANALYZED")
        assertThat(item.get("monster").get("emotion").asString()).isEqualTo("ANXIETY")
        assertThat(item.get("monster").get("hp").asInt()).isEqualTo(20)
        assertThat(item.get("monster").get("maxHp").asInt()).isEqualTo(20)
        assertThat(item.get("monster").get("status").asString()).isEqualTo("ALIVE")
        assertThat(item.get("likeCount").asInt()).isEqualTo(3)
        assertThat(item.get("likedByMe").asBoolean()).isTrue()
        assertThat(item.get("commentCount").asInt()).isEqualTo(2)
        val stored =
            jdbcTemplate.queryForObject("select created_at from posts where id = ?", Timestamp::class.java, newest)
        assertThat(Instant.parse(item.get("createdAt").asString())).isEqualTo(stored!!.toInstant())
        // 분석 전인 글은 피드와 같이 PENDING이고 몬스터가 없다
        val pending = first.get("items").get(1)
        assertThat(pending.get("analysisStatus").asString()).isEqualTo("PENDING")
        assertThat(pending.get("monster").isNull).isTrue()
        assertThat(pending.get("likedByMe").asBoolean()).isFalse()

        // 쪽 사이에 새 글을 써도 다음 쪽이 밀리지 않는다
        val inserted = insertPost(me, "사이에 끼어든 글")
        val second = data(list(POSTS, me, "cursor" to first.get("nextCursor").asString()))

        assertThat(postIds(second)).containsExactlyElementsOf(older.reversed().drop(19))
        assertThat(postIds(second)).doesNotContain(inserted, deleted, others)
        assertThat(second.get("nextCursor").isNull).isTrue()
    }

    @Test
    fun `US3-AC2 내 댓글과 답글이 최신순이고 글 앞부분이 붙으며 지운 댓글과 지운 글의 댓글은 없다`() {
        val me = members.onboarded()
        val other = members.onboarded()
        // 결합 이모지도 한 글자로 센다(003 research R8)
        val longContent = "👨‍👩‍👧" + "가".repeat(59)
        val post = insertPost(other, longContent)
        val deletedPost = insertPost(other, "지워질 글")
        val first = coreLoop.comment(me, post, "첫 댓글")
        val othersComment = coreLoop.comment(other, post, "남의 댓글")
        val reply = coreLoop.comment(me, post, "남의 댓글에 단 답글", parentId = othersComment)
        val removed = coreLoop.comment(me, post, "지울 댓글")
        coreLoop.removeComment(me, removed).andExpect(status().isNoContent)
        val onDeletedPost = coreLoop.comment(me, deletedPost, "지워질 글의 댓글")
        coreLoop.deletePost(deletedPost)
        // 원 댓글을 지우면 내 답글도 함께 지워진다
        val doomedParent = coreLoop.comment(other, post, "지워질 원 댓글")
        val doomedReply = coreLoop.comment(me, post, "함께 지워질 답글", parentId = doomedParent)
        coreLoop.removeComment(other, doomedParent).andExpect(status().isNoContent)
        val last = coreLoop.comment(me, post, "마지막 댓글")

        val page = data(list(COMMENTS, me))

        val items = page.get("items").values().toList()
        assertThat(items.map { it.get("commentId").asLong() }).containsExactly(last, reply, first)
        assertThat(items.map { it.get("commentId").asLong() }).doesNotContain(removed, onDeletedPost, doomedReply)
        assertThat(page.get("nextCursor").isNull).isTrue()
        val replyItem = items[1]
        assertThat(replyItem.get("postId").asLong()).isEqualTo(post)
        assertThat(replyItem.get("content").asString()).isEqualTo("남의 댓글에 단 답글")
        assertThat(replyItem.get("postContentPreview").asString()).isEqualTo("👨‍👩‍👧" + "가".repeat(49))
        assertThat(replyItem.get("reply").asBoolean()).isTrue()
        assertThat(replyItem.has("isReply")).isFalse()
        assertThat(items[2].get("reply").asBoolean()).isFalse()
        val stored =
            jdbcTemplate.queryForObject("select created_at from comments where id = ?", Timestamp::class.java, reply)
        assertThat(Instant.parse(replyItem.get("createdAt").asString())).isEqualTo(stored!!.toInstant())
    }

    @Test
    fun `US3-AC3 공감한 글은 공감 시각 최신순이고 취소한 공감과 지운 글은 없다`() {
        val me = members.onboarded()
        val author = members.onboarded(jobRole = "DESIGN", careerYear = "YEAR_5")
        // 글을 쓴 순서와 공감한 순서를 다르게 둔다
        val a = insertPost(author, "글 A")
        val b = insertPost(author, "글 B")
        val c = insertPost(author, "글 C")
        val cancelled = insertPost(author, "공감을 취소할 글")
        val deleted = insertPost(author, "지워질 글")
        val othersLike = insertPost(author, "남이 공감한 글")
        listOf(b, cancelled, a, deleted, c).forEach { coreLoop.likePost(me, it).andExpect(status().isOk) }
        coreLoop.unlikePost(me, cancelled).andExpect(status().isOk)
        coreLoop.deletePost(deleted)
        coreLoop.likePost(author, othersLike)

        val page = data(list(LIKED, me))

        assertThat(postIds(page)).containsExactly(c, a, b)
        assertThat(page.get("nextCursor").isNull).isTrue()
        val item = page.get("items").get(0)
        assertThat(item.get("author").get("id").asLong()).isEqualTo(author.id)
        assertThat(item.get("author").get("jobRole").asString()).isEqualTo("DESIGN")
        assertThat(item.get("author").get("careerYear").asString()).isEqualTo("YEAR_5")
        assertThat(item.get("contentPreview").asString()).isEqualTo("글 C")
        assertThat(item.get("likeCount").asInt()).isEqualTo(1)
        assertThat(item.get("likedByMe").asBoolean()).isTrue()
        assertThat(item.get("analysisStatus").asString()).isEqualTo("PENDING")

        // 취소했다가 다시 공감하면 다시 공감한 시각 자리(맨 앞)에 온다
        coreLoop.unlikePost(me, b).andExpect(status().isOk)
        coreLoop.likePost(me, b).andExpect(status().isOk)
        coreLoop.likePost(me, cancelled).andExpect(status().isOk)

        assertThat(postIds(data(list(LIKED, me)))).containsExactly(cancelled, b, c, a)
    }

    @Test
    fun `US3-AC4 항목이 없으면 빈 목록과 다음 커서 null`() {
        val me = members.onboarded()
        // 다른 회원의 활동이 있어도 내 목록은 비어 있다
        val other = members.onboarded()
        val post = insertPost(other, "남의 글")
        coreLoop.comment(other, post, "남의 댓글")
        coreLoop.likePost(other, post)

        listOf(POSTS, COMMENTS, LIKED).forEach { path ->
            val page = data(list(path, me))

            assertThat(page.get("items").isArray).isTrue()
            assertThat(page.get("items").size()).isZero()
            assertThat(page.has("nextCursor")).isTrue()
            assertThat(page.get("nextCursor").isNull).isTrue()
        }
    }

    @Test
    fun `세 목록 모두 커서로 끝까지 넘기면 중복과 누락이 없다`() {
        val me = members.onboarded()
        val other = members.onboarded()
        val myPosts = (1..5).map { insertPost(me, "내 글 $it") }
        val othersPosts = (1..5).map { insertPost(other, "남의 글 $it") }
        val myComments = othersPosts.map { coreLoop.comment(me, it, "댓글") }
        // 공감 시각이 같은 글이 쪽 경계에 걸쳐도 글 ID로 순서가 정해진다
        val sameInstant = Instant.now().truncatedTo(ChronoUnit.MICROS)
        othersPosts.take(3).forEach { insertLike(it, me, sameInstant) }
        othersPosts.drop(3).forEach { insertLike(it, me, sameInstant.plusSeconds(1)) }

        assertThat(walk(POSTS, me, "postId")).containsExactlyElementsOf(myPosts.reversed())
        assertThat(walk(COMMENTS, me, "commentId")).containsExactlyElementsOf(myComments.reversed())
        assertThat(walk(LIKED, me, "postId"))
            .containsExactlyElementsOf(othersPosts.drop(3).reversed() + othersPosts.take(3).reversed())
    }

    @Test
    fun `쿼리 수는 쪽 크기와 상관없이 글 목록 4개, 댓글 목록 1개다`() {
        val me = members.onboarded()
        val authors = (1..3).map { members.onboarded() }
        val myPosts = (1..21).map { insertPost(me, "내 글 $it") }
        val othersPosts = (1..21).map { insertPost(authors[it % authors.size], "남의 글 $it") }
        othersPosts.forEachIndexed { index, postId ->
            insertLike(postId, me, Instant.now().plusSeconds(index.toLong()).truncatedTo(ChronoUnit.MICROS))
            insertComment(postId, me, "댓글 $index")
        }
        val analyzed = coreLoop.createPost(me, "[짜증:낮음] 회의가 너무 길다")
        coreLoop.awaitMonster(analyzed)

        val (smallPosts, smallPostQueries) = QueryCounter.count { myPageQuery.posts(me.id, null, 5) }
        val (largePosts, largePostQueries) = QueryCounter.count { myPageQuery.posts(me.id, null, 20) }
        val (smallLiked, smallLikedQueries) = QueryCounter.count { myPageQuery.likedPosts(me.id, null, 5) }
        val (largeLiked, largeLikedQueries) = QueryCounter.count { myPageQuery.likedPosts(me.id, null, 20) }
        val (smallComments, smallCommentQueries) = QueryCounter.count { myPageQuery.comments(me.id, null, 5) }
        val (largeComments, largeCommentQueries) = QueryCounter.count { myPageQuery.comments(me.id, null, 20) }

        assertThat(myPosts).hasSize(21)
        assertThat(listOf(smallPosts.items, smallLiked.items, smallComments.items)).allSatisfy {
            assertThat(it).hasSize(5)
        }
        assertThat(listOf(largePosts.items, largeLiked.items, largeComments.items)).allSatisfy {
            assertThat(it).hasSize(20)
        }
        assertThat(listOf(smallPostQueries, largePostQueries, smallLikedQueries, largeLikedQueries))
            .containsOnly(4)
        assertThat(listOf(smallCommentQueries, largeCommentQueries)).containsOnly(1)
    }

    @Test
    fun `size는 1부터 50까지이고 벗어나거나 커서가 틀리면 400 INVALID_REQUEST`() {
        val me = members.onboarded()
        insertPost(me, "크기 확인")
        val badCursors = listOf("!!!", base64Url("abc"), base64Url("-1"), base64Url("0"), base64Url("1:2:3"))

        listOf(POSTS, COMMENTS, LIKED).forEach { path ->
            list(path, me, "size" to "1").andExpect(status().isOk)
            list(path, me, "size" to "50").andExpect(status().isOk)
            listOf("0", "51", "-1", "abc").forEach { size ->
                list(path, me, "size" to size)
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            }
            badCursors.forEach { cursor ->
                list(path, me, "cursor" to cursor)
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            }
        }
    }

    @Test
    fun `온보딩 전 회원은 세 목록 모두 403 ONBOARDING_REQUIRED, 토큰이 없으면 401`() {
        val signedUp = members.signedUp()

        listOf(POSTS, COMMENTS, LIKED).forEach { path ->
            list(path, signedUp)
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized)
        }
    }

    /** 커서를 따라 크기 2로 끝까지 넘기며 [idField]를 모은다. */
    private fun walk(
        path: String,
        member: TestMember,
        idField: String,
    ): List<Long> {
        val seen = mutableListOf<Long>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val params = listOfNotNull("size" to "2", cursor?.let { c -> "cursor" to c })
            val page = data(list(path, member, *params.toTypedArray()))
            seen += page.get("items").values().map { it.get(idField).asLong() }
            val next = page.get("nextCursor")
            if (next.isNull) return seen
            cursor = next.asString()
        }
        error("${MAX_PAGES}쪽 안에 끝나지 않았다")
    }

    /**
     * 감정 분석 없이 글을 바로 넣는다(분석 중으로 남는다). 작성 시각을 2시간 전으로 두어 작성 제한(1시간 10개)에 세지 않게
     * 한다. 목록 순서는 작성 시각이 아니라 글 ID로 정한다.
     */
    private fun insertPost(
        author: TestMember,
        content: String,
    ): Long {
        val profile =
            jdbcTemplate.queryForMap("select job_role, career_year from member where id = ?", author.id)
        val now = Timestamp.from(Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MICROS))
        return jdbcTemplate.queryForObject(
            """
            insert into posts (author_id, author_job_role, author_career_year, content, comment_tone, created_at,
                               updated_at)
            values (?, ?, ?, ?, 'COMFORT_ME', ?, ?)
            returning id
            """.trimIndent(),
            Long::class.java,
            author.id,
            profile["job_role"],
            profile["career_year"],
            content,
            now,
            now,
        )!!
    }

    private fun insertLike(
        postId: Long,
        member: TestMember,
        at: Instant = Instant.now(),
    ) {
        jdbcTemplate.update(
            "insert into post_likes (post_id, member_id, created_at) values (?, ?, ?)",
            postId,
            member.id,
            Timestamp.from(at),
        )
    }

    private fun insertComment(
        postId: Long,
        member: TestMember,
        content: String,
    ) {
        jdbcTemplate.update(
            "insert into comments (post_id, author_id, content, created_at, updated_at) values (?, ?, ?, now(), now())",
            postId,
            member.id,
            content,
        )
    }

    private fun list(
        path: String,
        member: TestMember,
        vararg params: Pair<String, String>,
    ): ResultActions {
        val request = get(path).bearer(member.accessToken)
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

    private fun postIds(page: JsonNode): List<Long> = page.get("items").values().map { it.get("postId").asLong() }

    private fun base64Url(raw: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val POSTS = "/api/v1/members/me/posts"
        const val COMMENTS = "/api/v1/members/me/comments"
        const val LIKED = "/api/v1/members/me/liked-posts"
        const val MAX_PAGES = 20
    }
}
