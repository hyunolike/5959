package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

/** V9__ai_companions.sql(009-ai-companions)의 스키마, 제약, 시드. 글 번호는 다른 테스트와 겹치지 않게 음수를 쓴다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CompanionMigrationTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `US2-AC3 AI 회원은 셋이고 닉네임만 있고 직군과 경력과 로그인 수단이 없다`() {
        val rows =
            jdbcTemplate.queryForList(
                "select nickname, nickname_key, auth_method, email, password_hash, job_role, career_year, role, " +
                    "onboarded_at is not null as onboarded from member where kind = 'AI' order by id",
            )

        assertThat(rows.map { it["nickname"] }).containsExactly("다정한 오구", "씩씩한 오구", "차분한 오구")
        assertThat(rows).allSatisfy { row ->
            assertThat(row["nickname_key"]).isEqualTo(row["nickname"])
            assertThat(row["auth_method"]).isEqualTo("NONE")
            assertThat(row["email"]).isNull()
            assertThat(row["password_hash"]).isNull()
            assertThat(row["job_role"]).isNull()
            assertThat(row["career_year"]).isNull()
            assertThat(row["role"]).isEqualTo("MEMBER")
            assertThat(row["onboarded"]).isEqualTo(true)
        }
        val identities =
            jdbcTemplate.queryForObject(
                "select count(*) from oauth_identity where member_id in (select id from member where kind = 'AI')",
                Long::class.java,
            )
        assertThat(identities).isZero()
    }

    @Test
    fun `US4-AC3 친구 셋은 AI 회원을 하나씩 가리키고 말씨가 서로 다르다`() {
        val rows =
            jdbcTemplate.queryForList(
                "select c.id, c.voice, m.nickname, m.kind from companion c join member m on m.id = c.member_id order by c.id",
            )

        assertThat(rows.map { it["voice"] }).containsExactly("GENTLE", "HEARTY", "CALM")
        assertThat(rows.map { it["nickname"] }).containsExactly("다정한 오구", "씩씩한 오구", "차분한 오구")
        assertThat(rows.map { it["kind"] }).containsOnly("AI")
        assertRejected { jdbcTemplate.update("insert into companion (id, member_id, voice) values (4, -1, 'GENTLE')") }
        assertRejected { jdbcTemplate.update("insert into companion (id, member_id, voice) values (4, -1, 'LOUD')") }
    }

    @Test
    fun `FR-016 AI 회원에게 직군이나 이메일이나 운영자 역할을 줄 수 없다`() {
        assertRejected { insertMember("직군있는", kind = "AI", authMethod = "NONE", jobRole = "DEVELOPMENT", careerYear = "YEAR_1") }
        assertRejected { insertMember("이메일있는", kind = "AI", authMethod = "NONE", email = "ai@example.com") }
        assertRejected { insertMember("로그인되는", kind = "AI", authMethod = "GOOGLE") }
        assertRejected { insertMember("수단없는사람", kind = "HUMAN", authMethod = "NONE") }
        assertRejected { insertMember("온보딩전", kind = "AI", authMethod = "NONE", onboarded = false) }
        assertRejected { insertMember("운영자", kind = "AI", authMethod = "NONE", role = "OPERATOR") }
        assertRejected { insertMember("모르는종류", kind = "BOT", authMethod = "NONE") }
    }

    @Test
    fun `사람 회원의 온보딩 제약은 그대로다`() {
        val suffix = (System.nanoTime() % 100_000).toString()

        insertMember("사람$suffix", kind = "HUMAN", authMethod = "GOOGLE", jobRole = "HR", careerYear = "YEAR_2")
        insertMember(nickname = null, kind = "HUMAN", authMethod = "GOOGLE", onboarded = false)

        assertRejected { insertMember("직군없$suffix", kind = "HUMAN", authMethod = "GOOGLE") }
        val kind =
            jdbcTemplate.queryForObject(
                "select column_default from information_schema.columns where table_name = 'member' and column_name = 'kind'",
                String::class.java,
            )
        assertThat(kind).contains("HUMAN")
    }

    @Test
    fun `FR-002 한 글의 반응 기록은 하나다`() {
        val post = -System.nanoTime()

        insertReaction(post)

        assertRejected { insertReaction(post) }
        assertThat(indexDef("companion_reaction_post_key")).contains("UNIQUE").contains("(post_id)")
        assertThat(indexDef("companion_reaction_due_idx")).contains("(post_created_at)").contains("'WAITING'")
        assertThat(indexDef("companion_reaction_done_idx")).contains("(companion_id, completed_at DESC)").contains("'DONE'")
    }

    @Test
    fun `US5-AC5 상태와 어긋난 반응 기록은 거절한다`() {
        val post = -System.nanoTime()

        assertRejected { insertReaction(post - 1, status = "SKIPPED", skipReason = null, next = null, completed = "now()") }
        assertRejected { insertReaction(post - 2, status = "WAITING", skipReason = "RISK") }
        assertRejected { insertReaction(post - 3, status = "DONE", commentId = null, next = null, completed = "now()") }
        assertRejected { insertReaction(post - 4, status = "WAITING", next = null) }
        assertRejected { insertReaction(post - 5, status = "WAITING", completed = "now()") }
        assertRejected { insertReaction(post - 6, status = "SKIPPED", skipReason = "BORED", next = null, completed = "now()") }
        assertRejected { insertReaction(post - 7, status = "REACTING") }
        insertReaction(post - 8, status = "DONE", commentId = 1, next = null, completed = "now()")
        for ((index, reason) in SKIP_REASONS.withIndex()) {
            insertReaction(post - 10 - index, status = "SKIPPED", skipReason = reason, next = null, completed = "now()")
        }
    }

    @Test
    fun `SC-007 하루의 쓴 몫은 날짜마다 하나이고 음수가 될 수 없다`() {
        jdbcTemplate.update("insert into companion_daily_usage (day, used) values ('1999-01-01', 0)")

        assertRejected { jdbcTemplate.update("insert into companion_daily_usage (day, used) values ('1999-01-01', 1)") }
        assertRejected { jdbcTemplate.update("update companion_daily_usage set used = -1 where day = '1999-01-01'") }
        jdbcTemplate.update("delete from companion_daily_usage where day = '1999-01-01'")
    }

    private fun assertRejected(insert: () -> Unit) {
        assertThatThrownBy { insert() }.isInstanceOf(DataAccessException::class.java)
    }

    private fun insertMember(
        nickname: String?,
        kind: String,
        authMethod: String,
        email: String? = null,
        jobRole: String? = null,
        careerYear: String? = null,
        onboarded: Boolean = true,
        role: String = "MEMBER",
    ) {
        jdbcTemplate.update(
            "insert into member (kind, auth_method, email, nickname, nickname_key, job_role, career_year, " +
                "onboarded_at, role, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, " +
                "${if (onboarded) "now()" else "null"}, ?, now(), now())",
            kind,
            authMethod,
            email,
            nickname,
            nickname,
            jobRole,
            careerYear,
            role,
        )
    }

    private fun insertReaction(
        postId: Long,
        status: String = "WAITING",
        skipReason: String? = null,
        commentId: Long? = null,
        next: String? = "now()",
        completed: String? = null,
    ) {
        jdbcTemplate.update(
            "insert into companion_reaction (post_id, post_created_at, status, skip_reason, comment_id, " +
                "next_attempt_at, created_at, completed_at) values (?, now(), ?, ?, ?, ${next ?: "null"}, now(), " +
                "${completed ?: "null"})",
            postId,
            status,
            skipReason,
            commentId,
        )
    }

    private fun indexDef(name: String): String =
        jdbcTemplate.queryForObject("select indexdef from pg_indexes where indexname = ?", String::class.java, name)!!

    companion object {
        private val SKIP_REASONS =
            listOf("HUMAN_COMMENT", "RISK", "GONE", "EXPIRED", "DAILY_LIMIT", "FAILED", "REJECTED")
    }
}
