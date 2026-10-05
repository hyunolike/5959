package com.ogu.post

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/** `PostApi.findComment`: 알림의 받는 사람을 정할 살아 있는 댓글(004 research R6). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class FindCommentTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var postApi: PostApi

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture

    @BeforeEach
    fun setUp() {
        val mockMvc =
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
    }

    @Test
    fun `원 댓글은 부모가 없고 답글은 원 댓글과 그 주인을 함께 준다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val replier = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(commenter, postId)
        val replyId = loop.comment(replier, postId, parentId = commentId)

        assertThat(postApi.findComment(commentId)).isEqualTo(CommentSummary(postId, commenter.id, null, null))
        assertThat(postApi.findComment(replyId)).isEqualTo(CommentSummary(postId, replier.id, commentId, commenter.id))
    }

    @Test
    fun `지운 댓글, 지운 글의 댓글, 없는 댓글은 null`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val deleted = loop.comment(commenter, postId)
        loop.deleteComment(deleted)
        val otherPost = loop.postWithoutMonster(author)
        val onDeletedPost = loop.comment(commenter, otherPost)
        loop.deletePost(otherPost)

        assertThat(postApi.findComment(deleted)).isNull()
        assertThat(postApi.findComment(onDeletedPost)).isNull()
        assertThat(postApi.findComment(Long.MAX_VALUE)).isNull()
    }
}
