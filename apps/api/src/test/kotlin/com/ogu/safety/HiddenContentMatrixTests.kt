package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.post.ContentType
import com.ogu.post.PostModerationApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
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
 * T013: 숨긴 글과 숨긴 댓글이 조회 경로마다 누구에게 보이는지(005 research R5). 조건이 한 곳이라도 빠지면 위기 글이 새어
 * 나가므로, 경로와 보는 사람(작성자, 다른 회원)을 곱해 모두 본다. 숨김은 파사드로 직접 걸어, 이미 반응이 쌓인 글과 답글이
 * 달린 댓글이 숨겨진 상태를 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class HiddenContentMatrixTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var moderation: PostModerationApi

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture
    private lateinit var safety: SafetyFixture

    private lateinit var author: TestMember
    private lateinit var other: TestMember
    private var hiddenPost = 0L
    private var otherCommentOnHiddenPost = 0L
    private var visiblePost = 0L
    private var hiddenComment = 0L
    private var replyToHiddenComment = 0L

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        author = members.onboarded()
        other = members.onboarded()

        // 반응이 쌓인 뒤 숨겨진 글: 몬스터, 다른 회원의 댓글과 공감, 작성자의 답글(다른 회원에게 답글 알림이 간다)
        hiddenPost = coreLoop.postWithMonster(author)
        otherCommentOnHiddenPost = coreLoop.comment(other, hiddenPost, "숨겨지기 전에 단 댓글")
        coreLoop.comment(author, hiddenPost, "숨겨지기 전에 단 답글", parentId = otherCommentOnHiddenPost)
        coreLoop.likePost(other, hiddenPost).andExpect(status().isOk)
        safety.awaitNotifications(other, "COMMENT_REPLY", 1)
        safety.awaitNotifications(author, "POST_COMMENT", 1)

        // 보이는 글에 달린, 답글이 있는 숨긴 댓글
        visiblePost = coreLoop.createPost(other, "[실패] 보이는 글")
        hiddenComment = coreLoop.comment(author, visiblePost, "곧 가려질 댓글")
        replyToHiddenComment = coreLoop.comment(other, visiblePost, "가려질 댓글에 단 답글", parentId = hiddenComment)

        assertThat(moderation.hide(ContentType.POST, hiddenPost, "OPERATOR")).isTrue()
        assertThat(moderation.hide(ContentType.COMMENT, hiddenComment, "OPERATOR")).isTrue()
    }

    @Test
    fun `US1-AC2 다른 회원의 피드, 상세, 공감한 글에 없고 주소로 열면 404 POST_NOT_FOUND`() {
        assertThat(safety.feedPostIds(other)).contains(visiblePost).doesNotContain(hiddenPost)
        assertThat(safety.feedPostIds(other, order = "POPULAR")).doesNotContain(hiddenPost)
        coreLoop
            .detail(other, hiddenPost)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        coreLoop.comments(other, hiddenPost).andExpect(status().isNotFound)
        assertThat(ids(other, "/api/v1/members/me/liked-posts", "postId")).doesNotContain(hiddenPost)
        // 숨겨진 글에 달았던 내 댓글도 내 댓글 목록에서 빠진다(지운 글의 댓글과 같다)
        assertThat(ids(other, "/api/v1/members/me/comments", "commentId")).doesNotContain(otherCommentOnHiddenPost)
    }

    @Test
    fun `피드는 글쓴이에게도 숨긴 글을 보이지 않는다`() {
        assertThat(safety.feedPostIds(author)).doesNotContain(hiddenPost)
        assertThat(safety.feedPostIds(author, order = "POPULAR")).doesNotContain(hiddenPost)
    }

    @Test
    fun `US1-AC3 작성자는 상세, 댓글, 내 글 목록, 감정 통계에서 숨긴 글을 보고 safety의 hidden이 true다`() {
        coreLoop
            .detail(author, hiddenPost)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.safety.hidden").value(true))
            .andExpect(jsonPath("$.data.monster.maxHp").value(10))
        coreLoop
            .comments(author, hiddenPost)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].commentId").value(otherCommentOnHiddenPost))

        val myPosts =
            safety
                .data(author, "/api/v1/members/me/posts")
                .get("items")
                .values()
                .toList()
        val mine = myPosts.first { it.get("postId").asLong() == hiddenPost }
        assertThat(mine.get("hidden").asBoolean()).isTrue()
        // 숨긴 내 글에 단 내 답글은 내 댓글 목록에 남는다
        val myComments =
            safety
                .data(author, "/api/v1/members/me/comments")
                .get("items")
                .values()
                .toList()
        assertThat(myComments.map { it.get("postId").asLong() }).contains(hiddenPost)
        val stats = safety.data(author, "/api/v1/members/me/emotion-stats")
        assertThat(stats.get("totalMonsters").asInt()).isEqualTo(1)
    }

    @Test
    fun `보이는 글의 목록 항목은 hidden이 false다`() {
        val feed =
            safety
                .data(other, "/api/v1/feed", "size" to "50")
                .get("items")
                .values()
                .toList()

        assertThat(feed.first { it.get("postId").asLong() == visiblePost }.get("hidden").asBoolean()).isFalse()
    }

    @Test
    fun `US1-AC5 숨긴 댓글은 다른 회원에게 hidden이고 content와 author가 null이며 답글은 그대로 보인다`() {
        val third = members.onboarded()

        coreLoop
            .comments(third, visiblePost)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].commentId").value(hiddenComment))
            .andExpect(jsonPath("$.data.items[0].hidden").value(true))
            .andExpect(jsonPath("$.data.items[0].content").value(null))
            .andExpect(jsonPath("$.data.items[0].author").value(null))
            .andExpect(jsonPath("$.data.items[0].mine").value(false))
            .andExpect(jsonPath("$.data.items[0].replies[0].commentId").value(replyToHiddenComment))
            .andExpect(jsonPath("$.data.items[0].replies[0].content").value("가려질 댓글에 단 답글"))
            .andExpect(jsonPath("$.data.items[0].replies[0].hidden").value(false))
        // 작성자 자신에게는 내용과 숨김 표시가 함께 보인다
        coreLoop
            .comments(author, visiblePost)
            .andExpect(jsonPath("$.data.items[0].content").value("곧 가려질 댓글"))
            .andExpect(jsonPath("$.data.items[0].hidden").value(true))
            .andExpect(jsonPath("$.data.items[0].safety.hidden").value(true))
        assertThat(ids(author, "/api/v1/members/me/comments", "commentId")).contains(hiddenComment)
    }

    @Test
    fun `US1-AC8 숨긴 글에 공감, 댓글, 댓글 공감은 404이고 HP가 그대로다`() {
        val third = members.onboarded()
        val hpBefore = coreLoop.monster(hiddenPost)!!.hp

        coreLoop
            .likePost(third, hiddenPost)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        coreLoop
            .writeComment(third, hiddenPost, "숨긴 글에 다는 댓글")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        coreLoop.likeComment(third, otherCommentOnHiddenPost).andExpect(status().isNotFound)
        coreLoop.unlikePost(other, hiddenPost).andExpect(status().isNotFound)
        // 글쓴이도 숨긴 글에는 댓글을 달 수 없다
        coreLoop.writeComment(author, hiddenPost, "글쓴이의 댓글").andExpect(status().isNotFound)

        assertThat(coreLoop.monster(hiddenPost)!!.hp).isEqualTo(hpBefore)
    }

    @Test
    fun `숨긴 댓글에는 공감과 새 답글을 받지 않는다`() {
        val third = members.onboarded()

        coreLoop
            .likeComment(third, hiddenComment)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        coreLoop
            .writeComment(third, visiblePost, "가려진 댓글에 다는 답글", parentId = hiddenComment)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"))
        // 숨기기 전에 달린 답글에는 공감할 수 있다
        coreLoop.likeComment(third, replyToHiddenComment).andExpect(status().isOk)
    }

    @Test
    fun `남이 쓴 숨긴 글과 댓글의 수정, 삭제는 403이 아니라 404다`() {
        coreLoop
            .updatePost(other, hiddenPost, mapOf("content" to "남이 고치기"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        coreLoop.removePost(other, hiddenPost).andExpect(status().isNotFound)
        coreLoop.updateComment(other, hiddenComment, "남이 고치기").andExpect(status().isNotFound)
        coreLoop.removeComment(other, hiddenComment).andExpect(status().isNotFound)
        // 보이는 것은 그대로 403이다
        coreLoop.updatePost(author, visiblePost, mapOf("content" to "남이 고치기")).andExpect(status().isForbidden)
    }

    @Test
    fun `작성자는 숨긴 자기 글과 댓글을 고치고 지울 수 있다`() {
        coreLoop.updatePost(author, hiddenPost, mapOf("content" to "[불안:낮음] 고친 글")).andExpect(status().isNoContent)
        coreLoop.updateComment(author, hiddenComment, "고친 댓글").andExpect(status().isNoContent)
        coreLoop.removeComment(author, hiddenComment).andExpect(status().isNoContent)
        coreLoop.removePost(author, hiddenPost).andExpect(status().isNoContent)

        coreLoop.detail(author, hiddenPost).andExpect(status().isNotFound)
    }

    @Test
    fun `US1-AC7 숨긴 글의 알림은 다른 회원에게 post가 null이고 작성자에게는 미리보기가 그대로다`() {
        val othersNotifications =
            safety
                .data(other, "/api/v1/notifications")
                .get("items")
                .values()
                .toList()
        val reply = othersNotifications.first { it.get("type").asString() == "COMMENT_REPLY" }
        assertThat(reply.get("postId").asLong()).isEqualTo(hiddenPost)
        assertThat(reply.get("post").isNull).isTrue()

        val authorsNotifications =
            safety
                .data(author, "/api/v1/notifications")
                .get("items")
                .values()
                .toList()
        val comment = authorsNotifications.first { it.get("type").asString() == "POST_COMMENT" }
        assertThat(comment.get("post").get("postId").asLong()).isEqualTo(hiddenPost)
    }

    @Test
    fun `숨김을 풀면 다른 회원에게 다시 보이고, 이미 풀린 것과 지운 것은 false를 돌려준다`() {
        assertThat(moderation.hide(ContentType.POST, hiddenPost, "OPERATOR")).isFalse()

        assertThat(moderation.unhide(ContentType.POST, hiddenPost)).isTrue()
        assertThat(moderation.unhide(ContentType.POST, hiddenPost)).isFalse()

        assertThat(safety.feedPostIds(other)).contains(hiddenPost)
        coreLoop.detail(other, hiddenPost).andExpect(status().isOk)
        assertThat(ids(other, "/api/v1/members/me/liked-posts", "postId")).contains(hiddenPost)

        coreLoop.removePost(author, hiddenPost).andExpect(status().isNoContent)
        assertThat(moderation.hide(ContentType.POST, hiddenPost, "OPERATOR")).isFalse()
        assertThat(moderation.contentOf(ContentType.POST, hiddenPost)).isNull()
    }

    private fun ids(
        member: TestMember,
        path: String,
        field: String,
    ): List<Long> =
        safety
            .data(member, path, "size" to "50")
            .get("items")
            .values()
            .map { it.get(field).asLong() }
}
