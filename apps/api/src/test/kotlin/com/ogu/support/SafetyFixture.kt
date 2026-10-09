package com.ogu.support

import org.awaitility.Awaitility.await
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 안전 기능 테스트 도우미(005). 문장은 모두 검증용으로 지어낸 것이다. 위기와 우려 표현은 V5 시드의 낱말에 걸린다.
 */
class SafetyFixture(
    private val mockMvc: MockMvc,
    private val jdbcTemplate: JdbcTemplate,
) {
    private val jsonMapper = JsonMapper.builder().build()

    /** 인증한 GET. 응답의 `data`를 돌려준다. */
    fun data(
        member: TestMember,
        path: String,
        vararg params: Pair<String, String>,
    ): JsonNode = jsonMapper.readTree(get(member, path, *params).andReturn().response.contentAsString).get("data")

    fun get(
        member: TestMember,
        path: String,
        vararg params: Pair<String, String>,
    ): ResultActions {
        val request = get(path).bearer(member.accessToken)
        params.forEach { (name, value) -> request.queryParam(name, value) }
        return mockMvc.perform(request)
    }

    /** 인증한 요청. [body]가 있으면 JSON으로 보낸다. */
    fun send(
        member: TestMember,
        method: HttpMethod,
        path: String,
        body: Any? = null,
    ): ResultActions {
        val request = request(method, path).bearer(member.accessToken)
        if (body != null) {
            val json = body as? String ?: jsonMapper.writeValueAsString(body)
            request.contentType(MediaType.APPLICATION_JSON).content(json)
        }
        return mockMvc.perform(request)
    }

    /** 운영자는 화면 없이 DB에서 지정한다(005 research R10). */
    fun grantOperator(member: TestMember) {
        jdbcTemplate.update("update member set role = 'OPERATOR' where id = ?", member.id)
    }

    fun revokeOperator(member: TestMember) {
        jdbcTemplate.update("update member set role = 'MEMBER' where id = ?", member.id)
    }

    /** 운영자 조회를 끝 쪽까지 이어 불러 모든 항목을 모은다. */
    fun allPages(
        operator: TestMember,
        path: String,
        vararg params: Pair<String, String>,
    ): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val paging = listOfNotNull(cursor?.let { "cursor" to it })
            val page = data(operator, path, *params, *paging.toTypedArray())
            items += page.get("items").values()
            cursor = page.get("nextCursor").takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        return items
    }

    /** 대상에 남은 운영자 처리 기록. 오래된 것부터. */
    fun actions(
        targetType: String,
        targetId: Long,
    ): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            "select * from moderation_action where target_type = ? and target_id = ? order by id",
            targetType,
            targetId,
        )

    fun feedPostIds(
        viewer: TestMember,
        order: String = "LATEST",
    ): List<Long> =
        data(viewer, "/api/v1/feed", "size" to "50", "order" to order)
            .get("items")
            .values()
            .map { it.get("postId").asLong() }

    /**
     * 감정 분석과 작성 제한을 거치지 않고 글을 바로 넣는다. 저장 이벤트가 나가지 않으므로 판정도 일어나지 않는다.
     * 숨김은 테스트가 파사드로 직접 건다.
     */
    fun insertPost(
        author: TestMember,
        content: String,
    ): Long {
        val profile = jdbcTemplate.queryForMap("select job_role, career_year from member where id = ?", author.id)
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

    fun postState(postId: Long): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            "select hidden_at is not null as hidden, hidden_reason, risk_level from posts where id = ?",
            postId,
        )

    fun commentState(commentId: Long): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            "select hidden_at is not null as hidden, hidden_reason, risk_level from comments where id = ?",
            commentId,
        )

    /** 받는 사람의 알림 가운데 [type]인 것의 멱등 키. */
    fun notificationKeys(
        receiver: TestMember,
        type: String,
    ): List<String> =
        jdbcTemplate.queryForList(
            "select dedup_key from notification where receiver_id = ? and type = ? order by seq",
            String::class.java,
            receiver.id,
            type,
        )

    fun awaitNotifications(
        receiver: TestMember,
        type: String,
        count: Int,
    ) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { notificationKeys(receiver, type).size == count }
    }

    /** 이 글에 관한 이벤트 가운데 아직 끝나지 않은 발행이 없을 때까지 기다린다(비동기 리스너가 모두 돌았다). */
    fun awaitListenersIdle() {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until {
            jdbcTemplate.queryForObject(
                "select count(*) from event_publication where completion_date is null",
                Int::class.java,
            ) == 0
        }
    }

    companion object {
        const val CRISIS_TEXT = "요즘 너무 힘들어서 죽고 싶어요"
        const val CONCERN_TEXT = "이제는 다 포기하고 싶어요"
        const val SAFE_TEXT = "오늘 회의가 너무 길어서 지쳤어요"
        val AWAIT_LIMIT: Duration = Duration.ofSeconds(10)
        val POLL: Duration = Duration.ofMillis(50)
    }
}
