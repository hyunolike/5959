package com.ogu.support

import org.awaitility.Awaitility.await
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/** 이메일과 refresh 토큰까지 아는 테스트 회원. 탈퇴 뒤 같은 계정으로 다시 로그인하거나 가입해 볼 때 쓴다. */
data class TestAccount(
    val member: TestMember,
    val email: String,
    val refreshToken: String,
)

/**
 * 탈퇴 테스트 도우미(013). 탈퇴는 실제 API로 하고, 커밋 뒤의 정리가 끝났는지는 Event Publication Registry에서 그 회원의
 * `MemberWithdrawn` 발행이 모두 끝났는지로 기다린다. 저장된 것은 읽기 전용 SQL로 확인한다.
 */
class WithdrawalFixture(
    private val mockMvc: MockMvc,
    private val jdbcTemplate: JdbcTemplate,
) {
    private val jsonMapper = JsonMapper.builder().build()

    fun withdraw(
        member: TestMember,
        body: Map<String, Any?> = mapOf("confirmed" to true),
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/members/me/withdrawal")
                .bearer(member.accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)),
        )

    /** 탈퇴하고 커밋 뒤의 정리까지 기다린다. */
    fun withdrawAndClean(member: TestMember) {
        withdraw(member).andExpect(status().isNoContent)
        awaitCleaned(member.id)
    }

    fun awaitCleaned(memberId: Long) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { pendingCleanups(memberId) == 0 }
    }

    /** 그 회원의 탈퇴 정리 가운데 아직 끝나지 않은 발행 수(리스너마다 하나). */
    fun pendingCleanups(memberId: Long): Int =
        jdbcTemplate.queryForObject(
            """
            select count(*) from event_publication
            where event_type = 'com.ogu.member.MemberWithdrawn'
              and completion_date is null
              and (serialized_event::jsonb ->> 'memberId') = ?
            """.trimIndent(),
            Int::class.java,
            memberId.toString(),
        )!!

    fun memberRow(memberId: Long): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            """
            select auth_method, email, password_hash, nickname, nickname_key, job_role, career_year, onboarded_at,
                   withdrawn_at
            from member where id = ?
            """.trimIndent(),
            memberId,
        )

    /** 본문이 남아 있는 글과 댓글의 수(지운 것 포함). 정리가 끝나면 0이다. */
    fun contentsLeft(memberId: Long): Int =
        count("select count(*) from posts where author_id = ? and content <> ''", memberId) +
            count("select count(*) from comments where author_id = ? and content <> ''", memberId)

    fun count(
        sql: String,
        vararg args: Any?,
    ): Int = jdbcTemplate.queryForObject(sql, Int::class.java, *args)!!

    /** 정해 둔 이메일로 가입한다. */
    fun signUp(email: String = "withdraw-${UUID.randomUUID()}@example.com"): TestAccount {
        val body = mapOf("email" to email, "password" to PASSWORD)
        val result =
            mockMvc
                .perform(
                    post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)),
                ).andExpect(status().isCreated)
        return account(data(result), email)
    }

    fun login(
        email: String,
        password: String = PASSWORD,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(mapOf("email" to email, "password" to password))),
        )

    /** 같은 계정으로 한 번 더 로그인한 세션(다른 기기). */
    fun loginAgain(account: TestAccount): TestAccount = account(data(login(account.email).andExpect(status().isOk)), account.email)

    fun onboard(
        account: TestAccount,
        nickname: String = uniqueNickname(),
    ): ResultActions =
        mockMvc.perform(
            put("/api/v1/members/me/onboarding")
                .bearer(account.member.accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    jsonMapper.writeValueAsString(
                        mapOf("nickname" to nickname, "jobRole" to "DEVELOPMENT", "careerYear" to "YEAR_3"),
                    ),
                ),
        )

    /** 온보딩을 마친 계정. 토큰은 온보딩 뒤에 새로 받은 것이다. */
    fun onboarded(
        email: String = "withdraw-${UUID.randomUUID()}@example.com",
        nickname: String = uniqueNickname(),
    ): TestAccount {
        val account = signUp(email)
        val token = data(onboard(account, nickname).andExpect(status().isOk)).get("accessToken").asString()
        return account.copy(member = account.member.copy(accessToken = token))
    }

    fun uniqueNickname(): String =
        UUID
            .randomUUID()
            .toString()
            .filter { it.isLetterOrDigit() }
            .map { 'a' + (it.digitToInt(16) % 26) }
            .take(8)
            .joinToString("")

    fun data(result: ResultActions): JsonNode = jsonMapper.readTree(result.andReturn().response.contentAsString).get("data")

    private fun account(
        data: JsonNode,
        email: String,
    ): TestAccount =
        TestAccount(
            member =
                TestMember(
                    id = data.get("member").get("id").asLong(),
                    accessToken = data.get("tokens").get("accessToken").asString(),
                ),
            email = email,
            refreshToken = data.get("tokens").get("refreshToken").asString(),
        )

    companion object {
        const val PASSWORD = "password123"
        val AWAIT_LIMIT: Duration = Duration.ofSeconds(20)
        val POLL: Duration = Duration.ofMillis(50)
    }
}
