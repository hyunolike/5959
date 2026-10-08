package com.ogu.post.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.HpLog
import com.ogu.support.MemberFixture
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T035: 글 공감과 댓글 공감(US3-AC1, AC4, AC6, AC8, FR-006, FR-007, FR-007a, research R5).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LikeApiTests {
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
    fun `US3-AC1 공감하면 공감 수 +1, HP −1, 다시 공감은 409 ALREADY_LIKED`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithMonster(author)

        loop
            .likePost(fan, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.likeCount").value(1))
            .andExpect(jsonPath("$.data.likedByMe").value(true))

        assertThat(loop.monster(postId)!!.hp).isEqualTo(9)
        assertThat(loop.hpLogs(postId))
            .containsExactly(HpLog(fan.id, "POST_LIKE", postId, 1, 10, 9, false))

        loop
            .likePost(fan, postId)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("ALREADY_LIKED"))
        assertThat(likeCount(postId)).isEqualTo(1)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(9)
    }

    @Test
    fun `US3-AC4 댓글 공감은 HP −1, 다시는 409`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithMonster(author)
        val commentId = loop.comment(commenter, postId)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(7)

        loop
            .likeComment(fan, commentId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.likeCount").value(1))
            .andExpect(jsonPath("$.data.likedByMe").value(true))

        assertThat(loop.monster(postId)!!.hp).isEqualTo(6)
        assertThat(loop.hpLogs(postId).last())
            .isEqualTo(HpLog(fan.id, "COMMENT_LIKE", commentId, 1, 7, 6, false))

        loop
            .likeComment(fan, commentId)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("ALREADY_LIKED"))
        assertThat(commentLikeCount(commentId)).isEqualTo(1)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(6)
    }

    @Test
    fun `US3-AC6 공감 취소 후 다시 공감해도 HP는 그대로`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithMonster(author)
        val commentId = loop.comment(commenter, postId)
        loop.likePost(fan, postId).andExpect(status().isOk)
        loop.likeComment(fan, commentId).andExpect(status().isOk)
        assertThat(loop.monster(postId)!!.hp).isEqualTo(5)

        loop
            .unlikePost(fan, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.likeCount").value(0))
            .andExpect(jsonPath("$.data.likedByMe").value(false))
        loop
            .unlikeComment(fan, commentId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.likeCount").value(0))
            .andExpect(jsonPath("$.data.likedByMe").value(false))
        assertThat(loop.monster(postId)!!.hp).isEqualTo(5)

        loop.likePost(fan, postId).andExpect(status().isOk).andExpect(jsonPath("$.data.likeCount").value(1))
        loop.likeComment(fan, commentId).andExpect(status().isOk).andExpect(jsonPath("$.data.likeCount").value(1))

        assertThat(loop.monster(postId)!!.hp).isEqualTo(5)
        assertThat(loop.hpLogs(postId)).hasSize(3)
    }

    @Test
    fun `공감하지 않은 글과 댓글의 공감 취소도 200이고 공감 수는 그대로다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(author, postId)

        loop
            .unlikePost(viewer, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.likeCount").value(0))
            .andExpect(jsonPath("$.data.likedByMe").value(false))
        loop
            .unlikeComment(viewer, commentId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.likeCount").value(0))
        assertThat(likeCount(postId)).isZero()
        assertThat(commentLikeCount(commentId)).isZero()
    }

    @Test
    fun `US3-AC8 작성자는 자기 글 공감 403 CANNOT_LIKE_OWN_POST, 자기 글 댓글 공감은 HP 변화 없음`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithMonster(author)
        val commentId = loop.comment(commenter, postId)
        val hpAfterComment = loop.monster(postId)!!.hp

        loop
            .likePost(author, postId)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CANNOT_LIKE_OWN_POST"))
        assertThat(likeCount(postId)).isZero()

        loop
            .likeComment(author, commentId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.likeCount").value(1))
        assertThat(loop.monster(postId)!!.hp).isEqualTo(hpAfterComment)
        assertThat(loop.hpLogs(postId).map { it.memberId }).doesNotContain(author.id)
    }

    @Test
    fun `삭제된 글과 댓글은 404`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(author, postId)
        val liveComment = loop.comment(author, postId)

        loop.deleteComment(commentId)
        loop
            .likeComment(fan, commentId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        loop
            .unlikeComment(fan, commentId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))

        loop.deletePost(postId)
        loop
            .likePost(fan, postId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        loop
            .unlikePost(fan, postId)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        // 지운 글의 댓글은 남아 있어도 공감할 수 없다
        loop
            .likeComment(fan, liveComment)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        loop
            .likePost(fan, Long.MAX_VALUE)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        loop
            .likeComment(fan, Long.MAX_VALUE)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        assertThat(likeCount(postId)).isZero()
    }

    @Test
    fun `ID 형식이 틀리면 400, 온보딩 전 회원은 403, 토큰이 없으면 401`() {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        loop
            .likePost(members.signedUp(), postId)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        mockMvc.perform(post("/api/v1/posts/{postId}/likes", postId)).andExpect(status().isUnauthorized)
        mockMvc
            .perform(post("/api/v1/comments/{commentId}/likes", "abc").bearer(author.accessToken))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
    }

    private fun likeCount(postId: Long): Int =
        jdbcTemplate.queryForObject("select like_count from posts where id = ?", Int::class.java, postId)!!

    private fun commentLikeCount(commentId: Long): Int =
        jdbcTemplate.queryForObject("select like_count from comments where id = ?", Int::class.java, commentId)!!
}
