package com.ogu.post.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.emotion.EmotionType
import com.ogu.monster.MonsterApi
import com.ogu.monster.MonsterStatus
import com.ogu.monster.MonsterView
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T046: 글과 댓글 수정, 삭제(US4-AC1~AC4, FR-013, FR-014). 수정과 삭제는 몬스터를 바꾸지 않고 HP도 돌려주지 않는다.
 * 원 댓글을 지우면 살아 있던 답글도 함께 지우고 댓글 수를 실제로 지운 개수만큼 줄인다(data-model.md).
 * 다른 요청과 겹친 수정, 삭제는 com.ogu.post.PostManageRaceTest가 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PostManageApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
    }

    @Test
    fun `US4-AC1 본문과 말투를 고쳐도 몬스터 감정, HP, 상태는 그대로`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithMonster(author)
        loop.comment(commenter, postId)
        val before = loop.monster(postId)
        assertThat(before).isEqualTo(MonsterView(EmotionType.ANXIETY, 7, 10, MonsterStatus.ALIVE))

        loop
            .updatePost(author, postId, mapOf("content" to "  고친 본문이에요  ", "commentTone" to "MAKE_ME_LAUGH"))
            .andExpect(status().isNoContent)

        loop
            .detail(author, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.content").value("고친 본문이에요"))
            .andExpect(jsonPath("$.data.commentTone").value("MAKE_ME_LAUGH"))
            .andExpect(jsonPath("$.data.monster.hp").value(7))
        assertThat(loop.monster(postId)).isEqualTo(before)
        assertThat(loop.hpLogs(postId)).hasSize(1)

        // 한쪽만 고치면 다른 쪽은 그대로다
        loop.updatePost(author, postId, mapOf("commentTone" to "WARM_ADVICE")).andExpect(status().isNoContent)
        loop.updatePost(author, postId, mapOf("content" to "본문만")).andExpect(status().isNoContent)
        val row = jdbcTemplate.queryForMap("select content, comment_tone from posts where id = ?", postId)
        assertThat(row["content"]).isEqualTo("본문만")
        assertThat(row["comment_tone"]).isEqualTo("WARM_ADVICE")
        assertThat(loop.monster(postId)).isEqualTo(before)
    }

    @Test
    fun `US4-AC1 처치된 몬스터의 글을 고쳐도 처치 상태 그대로`() {
        val author = members.onboarded()
        val postId = loop.postWithMonster(author)
        repeat(4) { loop.comment(members.onboarded(), postId) }
        val defeated = MonsterView(EmotionType.ANXIETY, 0, 10, MonsterStatus.DEFEATED)
        assertThat(loop.monster(postId)).isEqualTo(defeated)

        loop
            .updatePost(author, postId, mapOf("content" to "[기쁨:높음] 다른 감정처럼 보이는 본문"))
            .andExpect(status().isNoContent)

        assertThat(loop.monster(postId)).isEqualTo(defeated)
    }

    @ParameterizedTest
    @MethodSource("invalidPostUpdates")
    fun `글 수정 검증은 작성과 같은 규칙이다(빈 본문, 공백만, 501자, 결합 문자 과다, 목록에 없는 말투, 빈 요청은 400)`(body: String) {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        patchPostRaw(author, postId, body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))

        val row = jdbcTemplate.queryForMap("select content, comment_tone from posts where id = ?", postId)
        assertThat(row["content"]).isEqualTo("[실패] 분석 중인 글")
        assertThat(row["comment_tone"]).isEqualTo("COMFORT_ME")
    }

    @Test
    fun `글 수정 500자 경계는 사람이 보는 글자로 센다`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        loop.updatePost(author, postId, mapOf("content" to "👨‍👩‍👧".repeat(500))).andExpect(status().isNoContent)
        loop.updatePost(author, postId, mapOf("content" to "👍".repeat(501))).andExpect(status().isBadRequest)
    }

    @Test
    fun `수정은 1시간 작성 제한에 들어가지 않는다`() {
        val author = members.onboarded()
        val postIds = (1..10).map { loop.createPost(author, "[실패] 고민 $it") }

        loop.updatePost(author, postIds.first(), mapOf("content" to "고친 글")).andExpect(status().isNoContent)
        loop.updatePost(author, postIds.last(), mapOf("content" to "또 고친 글")).andExpect(status().isNoContent)
    }

    @Test
    fun `US4-AC2 삭제하면 피드와 상세에서 사라지고 상세는 404`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithMonster(author)
        loop.likePost(fan, postId).andExpect(status().isOk)
        assertThat(feedPostIds(viewer)).contains(postId)

        loop.removePost(author, postId).andExpect(status().isNoContent)

        assertThat(feedPostIds(viewer)).doesNotContain(postId)
        loop
            .detail(viewer, postId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        // 몬스터와 HP는 그대로 남는다(삭제해도 HP는 돌아오지 않는다)
        assertThat(loop.monster(postId)).isEqualTo(MonsterView(EmotionType.ANXIETY, 9, 10, MonsterStatus.ALIVE))
        // 지운 글은 다시 지우거나 고칠 수 없다
        loop
            .removePost(author, postId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        loop
            .updatePost(author, postId, mapOf("content" to "되살리기"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
    }

    @Test
    fun `US4-AC3 원 댓글을 지우면 답글도 지워지고 댓글 수가 그만큼 준다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val replier = members.onboarded()
        val postId = loop.postWithMonster(author, intensity = "높음")
        val rootId = loop.comment(commenter, postId, "원 댓글")
        loop.comment(replier, postId, "답글 하나", parentId = rootId)
        val deletedReply = loop.comment(replier, postId, "먼저 지울 답글", parentId = rootId)
        loop.comment(author, postId, "작성자 답글", parentId = rootId)
        val otherRoot = loop.comment(replier, postId, "다른 원 댓글")
        val hp = loop.monster(postId)!!.hp
        assertThat(commentCount(postId)).isEqualTo(5)

        // 답글을 지우면 1만 준다
        loop.removeComment(replier, deletedReply).andExpect(status().isNoContent)
        assertThat(commentCount(postId)).isEqualTo(4)

        // 원 댓글을 지우면 살아 있던 답글 둘과 함께 3이 준다(먼저 지운 답글은 다시 세지 않는다)
        loop.removeComment(commenter, rootId).andExpect(status().isNoContent)

        assertThat(commentCount(postId)).isEqualTo(1)
        val liveIds =
            jdbcTemplate.queryForList(
                "select id from comments where post_id = ? and deleted_at is null",
                Long::class.java,
                postId,
            )
        assertThat(liveIds).containsExactly(otherRoot)
        loop
            .comments(author, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].commentId").value(otherRoot))
        assertThat(loop.monster(postId)!!.hp).isEqualTo(hp)
        loop
            .removeComment(commenter, rootId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
    }

    @Test
    fun `원 댓글을 지울 때 함께 지워진 답글은 고치거나 지우면 404 COMMENT_NOT_FOUND`() {
        val author = members.onboarded()
        val replier = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val rootId = loop.comment(author, postId, "원 댓글")
        val replyId = loop.comment(replier, postId, "답글", parentId = rootId)
        loop.removeComment(author, rootId).andExpect(status().isNoContent)

        loop
            .updateComment(replier, replyId, "지워진 답글 고치기")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        loop
            .removeComment(replier, replyId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        assertThat(commentCount(postId)).isZero()
        val content =
            jdbcTemplate.queryForObject("select content from comments where id = ?", String::class.java, replyId)
        assertThat(content).isEqualTo("답글")
    }

    @Test
    fun `US4-AC3 댓글을 고치면 반영되고 HP와 댓글 수는 그대로다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithMonster(author)
        val rootId = loop.comment(commenter, postId, "원 댓글")
        val replyId = loop.comment(commenter, postId, "답글", parentId = rootId)

        loop.updateComment(commenter, rootId, "  고친 원 댓글  ").andExpect(status().isNoContent)
        loop.updateComment(commenter, replyId, "고친 답글").andExpect(status().isNoContent)

        loop
            .comments(author, postId)
            .andExpect(jsonPath("$.data.items[0].content").value("고친 원 댓글"))
            .andExpect(jsonPath("$.data.items[0].replies[0].content").value("고친 답글"))
        assertThat(loop.monster(postId)!!.hp).isEqualTo(7)
        assertThat(commentCount(postId)).isEqualTo(2)
    }

    @Test
    fun `댓글 수정 검증은 작성과 같은 규칙이다(빈 본문, 공백만, 301자, 결합 문자 과다는 400)`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(author, postId, "원래 댓글")
        val zalgo = ("a" + "́".repeat(999)).repeat(3) + "́"

        listOf("", "   ", "가".repeat(301), zalgo).forEach { content ->
            loop
                .updateComment(author, commentId, content)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
        patchCommentRaw(author, commentId, "{}")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        loop.updateComment(author, commentId, "👍".repeat(300)).andExpect(status().isNoContent)
    }

    @Test
    fun `US4-AC4 남의 글과 댓글 수정, 삭제는 403 NOT_AUTHOR`() {
        val author = members.onboarded()
        val other = members.onboarded()
        val postId = loop.postWithMonster(author)
        val commentId = loop.comment(author, postId, "작성자 댓글")
        val hp = loop.monster(postId)

        listOf(
            loop.updatePost(other, postId, mapOf("content" to "남이 고침")),
            loop.removePost(other, postId),
            loop.updateComment(other, commentId, "남이 고친 댓글"),
            loop.removeComment(other, commentId),
        ).forEach { result ->
            result
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_AUTHOR"))
        }

        val post = jdbcTemplate.queryForMap("select content, deleted_at from posts where id = ?", postId)
        assertThat(post["content"]).isEqualTo("[불안:낮음] 몬스터가 있는 글")
        assertThat(post["deleted_at"]).isNull()
        val comment = jdbcTemplate.queryForMap("select content, deleted_at from comments where id = ?", commentId)
        assertThat(comment["content"]).isEqualTo("작성자 댓글")
        assertThat(comment["deleted_at"]).isNull()
        assertThat(commentCount(postId)).isEqualTo(1)
        assertThat(loop.monster(postId)).isEqualTo(hp)
    }

    @Test
    fun `없거나 지운 글과 댓글은 404, 지운 글의 댓글도 404 COMMENT_NOT_FOUND`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(author, postId)

        loop
            .updatePost(author, Long.MAX_VALUE, mapOf("content" to "없는 글"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        loop
            .removePost(author, Long.MAX_VALUE)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        loop
            .updateComment(author, Long.MAX_VALUE, "없는 댓글")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        loop
            .removeComment(author, Long.MAX_VALUE)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))

        loop.removePost(author, postId).andExpect(status().isNoContent)
        loop
            .updateComment(author, commentId, "지운 글의 댓글")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        loop
            .removeComment(author, commentId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
    }

    @Test
    fun `ID 형식이 틀리면 400, 토큰이 없으면 401`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        mockMvc
            .perform(delete("/api/v1/posts/{postId}", "abc").bearer(author.accessToken))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        mockMvc
            .perform(delete("/api/v1/comments/{commentId}", "abc").bearer(author.accessToken))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        patchPostRawPath(author, "abc", """{"content":"x"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        mockMvc.perform(delete("/api/v1/posts/{postId}", postId)).andExpect(status().isUnauthorized)
    }

    private fun feedPostIds(viewer: TestMember): List<Long> =
        loop
            .data(
                mockMvc
                    .perform(get("/api/v1/feed").param("size", "50").bearer(viewer.accessToken))
                    .andExpect(status().isOk),
            ).get("items")
            .toList()
            .map { item -> item.get("postId").asLong() }

    private fun patchPostRaw(
        member: TestMember,
        postId: Long,
        body: String,
    ): ResultActions = patchPostRawPath(member, postId.toString(), body)

    private fun patchPostRawPath(
        member: TestMember,
        postId: String,
        body: String,
    ): ResultActions =
        mockMvc.perform(
            patch("/api/v1/posts/{postId}", postId)
                .bearer(member.accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun patchCommentRaw(
        member: TestMember,
        commentId: Long,
        body: String,
    ): ResultActions =
        mockMvc.perform(
            patch("/api/v1/comments/{commentId}", commentId)
                .bearer(member.accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun commentCount(postId: Long): Int =
        jdbcTemplate.queryForObject("select comment_count from posts where id = ?", Int::class.java, postId)!!

    companion object {
        @JvmStatic
        fun invalidPostUpdates(): List<String> {
            val zalgo = ("a" + "́".repeat(999)).repeat(5) + "́"
            return listOf(
                """{"content":""}""",
                """{"content":"   "}""",
                """{"content":"${"가".repeat(501)}"}""",
                """{"content":"$zalgo"}""",
                """{"commentTone":"SHOUT_AT_ME"}""",
                """{}""",
                """{"content":null,"commentTone":null}""",
            )
        }
    }
}
