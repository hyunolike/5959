package com.ogu.support

import com.ogu.monster.MonsterApi
import com.ogu.monster.MonsterView
import org.awaitility.Awaitility.await
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** HP 기록 한 줄(monster_hp_log). */
data class HpLog(
    val memberId: Long,
    val action: String,
    val targetId: Long,
    val hpDelta: Int,
    val hpBefore: Int,
    val hpAfter: Int,
    val retroactive: Boolean,
)

/**
 * 공감, 댓글, 몬스터를 실제 API로 다루는 테스트 도우미. 몬스터와 HP 기록은 [MonsterApi]와 읽기 전용 SQL로 확인한다.
 * 가짜 분석기(research R3)의 머리말로 감정과 강도를 정한다: `[불안:낮음]`이면 최대 HP 10, `[실패]`면 분석 중에 머문다.
 */
class CoreLoopFixture(
    private val mockMvc: MockMvc,
    private val jdbcTemplate: JdbcTemplate,
    private val monsterApi: MonsterApi,
) {
    private val jsonMapper = JsonMapper.builder().build()

    fun createPost(
        author: TestMember,
        content: String,
    ): Long {
        val body = mapOf("content" to content, "commentTone" to "COMFORT_ME")
        val response =
            mockMvc
                .perform(
                    post("/api/v1/posts")
                        .bearer(author.accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)),
                ).andExpect(status().isCreated)
        return data(response).get("postId").asLong()
    }

    /** 분석이 끝나 몬스터가 생긴 글. [intensity]는 가짜 분석기 머리말의 강도(낮음 10, 보통 20, 높음 30)다. */
    fun postWithMonster(
        author: TestMember,
        intensity: String = "낮음",
    ): Long {
        val postId = createPost(author, "[불안:$intensity] 몬스터가 있는 글")
        awaitMonster(postId)
        return postId
    }

    /** 분석이 계속 실패해 몬스터가 없는 글. */
    fun postWithoutMonster(author: TestMember): Long = createPost(author, "[실패] 분석 중인 글")

    fun awaitMonster(postId: Long): MonsterView {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { monster(postId) != null }
        return monster(postId)!!
    }

    fun monster(postId: Long): MonsterView? = monsterApi.findByPostIds(listOf(postId))[postId]

    fun likePost(
        member: TestMember,
        postId: Long,
    ): ResultActions = mockMvc.perform(post("/api/v1/posts/{postId}/likes", postId).bearer(member.accessToken))

    fun unlikePost(
        member: TestMember,
        postId: Long,
    ): ResultActions = mockMvc.perform(delete("/api/v1/posts/{postId}/likes/me", postId).bearer(member.accessToken))

    fun likeComment(
        member: TestMember,
        commentId: Long,
    ): ResultActions = mockMvc.perform(post("/api/v1/comments/{commentId}/likes", commentId).bearer(member.accessToken))

    fun unlikeComment(
        member: TestMember,
        commentId: Long,
    ): ResultActions {
        val request = delete("/api/v1/comments/{commentId}/likes/me", commentId)
        return mockMvc.perform(request.bearer(member.accessToken))
    }

    fun writeComment(
        member: TestMember,
        postId: Long,
        content: String,
        parentId: Long? = null,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/posts/{postId}/comments", postId)
                .bearer(member.accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(mapOf("content" to content, "parentId" to parentId))),
        )

    /** 댓글을 달고 새 댓글 ID를 돌려준다. */
    fun comment(
        member: TestMember,
        postId: Long,
        content: String = "힘내요",
        parentId: Long? = null,
    ): Long =
        data(writeComment(member, postId, content, parentId).andExpect(status().isCreated))
            .get("commentId")
            .asLong()

    fun comments(
        member: TestMember,
        postId: Long,
        cursor: String? = null,
    ): ResultActions {
        val request = get("/api/v1/posts/{postId}/comments", postId).bearer(member.accessToken)
        if (cursor != null) request.param("cursor", cursor)
        return mockMvc.perform(request)
    }

    /** US4(댓글 삭제 API) 전이라 저장소에서 지운다. 원 댓글이면 답글도 함께 지우고 댓글 수를 맞춘다(data-model.md). */
    fun deleteComment(commentId: Long) {
        val postId =
            jdbcTemplate.queryForObject("select post_id from comments where id = ?", Long::class.java, commentId)
        val deleted =
            jdbcTemplate.update(
                "update comments set deleted_at = now() where (id = ? or parent_id = ?) and deleted_at is null",
                commentId,
                commentId,
            )
        jdbcTemplate.update("update posts set comment_count = comment_count - ? where id = ?", deleted, postId)
    }

    fun deletePost(postId: Long) {
        jdbcTemplate.update("update posts set deleted_at = now() where id = ?", postId)
    }

    fun hpLogs(postId: Long): List<HpLog> =
        jdbcTemplate.query(
            """
            select l.member_id, l.action, l.target_id, l.hp_delta, l.hp_before, l.hp_after, l.retroactive
            from monster_hp_log l join monsters m on m.id = l.monster_id
            where m.post_id = ?
            order by l.id
            """.trimIndent(),
            { rs, _ ->
                HpLog(
                    memberId = rs.getLong("member_id"),
                    action = rs.getString("action"),
                    targetId = rs.getLong("target_id"),
                    hpDelta = rs.getInt("hp_delta"),
                    hpBefore = rs.getInt("hp_before"),
                    hpAfter = rs.getInt("hp_after"),
                    retroactive = rs.getBoolean("retroactive"),
                )
            },
            postId,
        )

    fun data(result: ResultActions): JsonNode {
        val body = result.andReturn().response.contentAsString
        return jsonMapper.readTree(body).get("data")
    }

    companion object {
        val AWAIT_LIMIT: Duration = Duration.ofSeconds(10)
        val POLL: Duration = Duration.ofMillis(50)
    }
}
