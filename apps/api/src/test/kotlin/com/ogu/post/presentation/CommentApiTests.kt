package com.ogu.post.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.HpLog
import com.ogu.support.MemberFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T036: 댓글과 답글(US3-AC2, AC3, AC8, FR-006, FR-008, FR-012, research R5, R8).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CommentApiTests {
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
    fun `US3-AC2 첫 댓글은 HP −3, 두 번째 댓글은 HP 그대로`() {
        val author = members.onboarded()
        val commenter = members.onboarded(jobRole = "DESIGN", careerYear = "YEAR_1")
        val postId = loop.postWithMonster(author)
        val nickname =
            jdbcTemplate.queryForObject("select nickname from member where id = ?", String::class.java, commenter.id)

        val first =
            loop
                .writeComment(commenter, postId, "  힘내세요  ")
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.commentId").isNumber)
                .andExpect(jsonPath("$.data.author.id").value(commenter.id))
                .andExpect(jsonPath("$.data.author.nickname").value(nickname))
                .andExpect(jsonPath("$.data.author.jobRole").value("DESIGN"))
                .andExpect(jsonPath("$.data.author.careerYear").value("YEAR_1"))
                .andExpect(jsonPath("$.data.content").value("힘내세요"))
                .andExpect(jsonPath("$.data.likeCount").value(0))
                .andExpect(jsonPath("$.data.likedByMe").value(false))
                .andExpect(jsonPath("$.data.mine").value(true))
                .andExpect(jsonPath("$.data.createdAt").isString)
                .andExpect(jsonPath("$.data.replies").isEmpty)
                .let { loop.data(it).get("commentId").asLong() }

        assertThat(loop.monster(postId)!!.hp).isEqualTo(7)
        assertThat(commentCount(postId)).isEqualTo(1)

        loop.comment(commenter, postId, "또 왔어요")

        assertThat(loop.monster(postId)!!.hp).isEqualTo(7)
        assertThat(commentCount(postId)).isEqualTo(2)
        assertThat(loop.hpLogs(postId)).containsExactly(HpLog(commenter.id, "COMMENT", postId, 3, 10, 7, false))
        assertThat(first).isPositive()
    }

    @Test
    fun `US3-AC3 답글은 원 댓글 아래, 답글의 답글은 400 INVALID_PARENT_COMMENT`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val replier = members.onboarded()
        val postId = loop.postWithMonster(author)
        val rootId = loop.comment(commenter, postId, "원 댓글")

        val replyId = loop.comment(replier, postId, "답글", parentId = rootId)

        // 답글도 그 회원의 이 글 첫 댓글이면 HP를 3 줄인다
        assertThat(loop.monster(postId)!!.hp).isEqualTo(4)
        loop
            .comments(replier, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].commentId").value(rootId))
            .andExpect(jsonPath("$.data.items[0].replies.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].replies[0].commentId").value(replyId))
            .andExpect(jsonPath("$.data.items[0].replies[0].content").value("답글"))
            .andExpect(jsonPath("$.data.items[0].replies[0].mine").value(true))
            .andExpect(jsonPath("$.data.items[0].replies[0].replies").isEmpty)
            .andExpect(jsonPath("$.data.items[0].mine").value(false))

        loop
            .writeComment(commenter, postId, "답글의 답글", parentId = replyId)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_PARENT_COMMENT"))
        assertThat(commentCount(postId)).isEqualTo(2)
    }

    @Test
    fun `원 댓글이 없거나 지웠거나 다른 글의 댓글이면 답글은 404 COMMENT_NOT_FOUND`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val otherPostId = loop.postWithoutMonster(author)
        val deletedRoot = loop.comment(commenter, postId)
        val otherRoot = loop.comment(commenter, otherPostId)
        loop.deleteComment(deletedRoot)

        listOf(deletedRoot, otherRoot, Long.MAX_VALUE).forEach { parentId ->
            loop
                .writeComment(commenter, postId, "답글", parentId = parentId)
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        }
        assertThat(commentCount(postId)).isZero()
    }

    @Test
    fun `첫 댓글을 지우고 다시 달아도 HP 그대로`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithMonster(author)
        val first = loop.comment(commenter, postId)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(7)

        loop.deleteComment(first)
        loop.comment(commenter, postId, "다시 달아요")

        assertThat(loop.monster(postId)!!.hp).isEqualTo(7)
        assertThat(loop.hpLogs(postId)).hasSize(1)
    }

    @Test
    fun `300자 경계`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        // 결합 이모지도 한 글자다(research R8)
        loop.writeComment(author, postId, "👨‍👩‍👧".repeat(300)).andExpect(status().isCreated)
        loop.writeComment(author, postId, "가".repeat(300)).andExpect(status().isCreated)
        listOf("가".repeat(301), "   ", "", "e" + "́".repeat(3000)).forEach { content ->
            loop
                .writeComment(author, postId, content)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
        assertThat(commentCount(postId)).isEqualTo(2)
    }

    @Test
    fun `US3-AC8 작성자 댓글은 HP 변화 없음`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithMonster(author)
        val rootId = loop.comment(commenter, postId)

        loop.comment(author, postId, "고마워요")
        loop.comment(author, postId, "답글로도 고마워요", parentId = rootId)

        assertThat(loop.monster(postId)!!.hp).isEqualTo(7)
        assertThat(commentCount(postId)).isEqualTo(3)
        assertThat(loop.hpLogs(postId).map { it.memberId }).containsExactly(commenter.id)
    }

    @Test
    fun `댓글 목록은 원 댓글 오래된 순 50개 커서와 답글, 지운 댓글과 지운 원 댓글의 답글 제외`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val roots = (1..52).map { loop.comment(author, postId, "원 댓글 $it") }
        val reply1 = loop.comment(viewer, postId, "첫 답글", parentId = roots[0])
        val deletedReply = loop.comment(viewer, postId, "지울 답글", parentId = roots[0])
        val reply2 = loop.comment(author, postId, "둘째 답글", parentId = roots[0])
        loop.comment(viewer, postId, "지운 원 댓글의 답글", parentId = roots[1])
        loop.deleteComment(deletedReply)
        loop.deleteComment(roots[1])
        jdbcTemplate.update(
            "insert into comment_likes (comment_id, member_id, created_at) values (?, ?, now())",
            reply1,
            viewer.id,
        )

        val first =
            loop
                .comments(viewer, postId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.items.length()").value(50))
                .andExpect(jsonPath("$.data.items[0].commentId").value(roots[0]))
                .andExpect(jsonPath("$.data.items[0].replies.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].replies[0].commentId").value(reply1))
                .andExpect(jsonPath("$.data.items[0].replies[0].likedByMe").value(true))
                .andExpect(jsonPath("$.data.items[0].replies[1].commentId").value(reply2))
                .andExpect(jsonPath("$.data.items[0].replies[1].likedByMe").value(false))
                .andExpect(jsonPath("$.data.items[1].commentId").value(roots[2]))
                .andExpect(jsonPath("$.data.items[49].commentId").value(roots[50]))
                .andExpect(jsonPath("$.data.nextCursor").isString)
                .let(loop::data)

        loop
            .comments(viewer, postId, first.get("nextCursor").asString())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].commentId").value(roots[51]))
            .andExpect(jsonPath("$.data.items[0].replies").isEmpty)
            .andExpect(jsonPath("$.data.nextCursor").value(null as Any?))
    }

    @Test
    fun `댓글 커서가 올바르지 않으면 400 INVALID_REQUEST`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        listOf("abc!", "eA", "LTE", "MA").forEach { cursor ->
            loop
                .comments(author, postId, cursor)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `지운 글이나 없는 글의 댓글 목록과 작성은 404 POST_NOT_FOUND`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.deletePost(postId)

        listOf(postId, Long.MAX_VALUE).forEach { id ->
            loop
                .comments(author, id)
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
            loop
                .writeComment(author, id, "댓글")
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        }
    }

    @Test
    fun `몬스터가 없는 글에도 댓글을 달 수 있고 HP 기록은 남지 않는다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        loop.comment(commenter, postId)

        assertThat(commentCount(postId)).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from monster_hp_log where member_id = ?",
                Int::class.java,
                commenter.id,
            ),
        ).isZero()
    }

    private fun commentCount(postId: Long): Int =
        jdbcTemplate.queryForObject("select comment_count from posts where id = ?", Int::class.java, postId)!!
}
