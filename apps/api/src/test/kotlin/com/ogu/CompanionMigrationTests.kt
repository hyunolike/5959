package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

/**
 * V9__ai_companions.sql(009-ai-companions)와 V11__companion_multi_reaction.sql(012-companion-activity)의 스키마, 제약, 시드.
 * 글 번호는 다른 테스트와 겹치지 않게 음수를 쓴다.
 */
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
                "select c.id, c.voice, m.nickname, m.kind from companion c " +
                    "join member m on m.id = c.member_id order by c.id",
            )

        assertThat(rows.map { it["voice"] }).containsExactly("GENTLE", "HEARTY", "CALM")
        assertThat(rows.map { it["nickname"] }).containsExactly("다정한 오구", "씩씩한 오구", "차분한 오구")
        assertThat(rows.map { it["kind"] }).containsOnly("AI")
        assertRejected { jdbcTemplate.update("insert into companion (id, member_id, voice) values (4, -1, 'GENTLE')") }
        assertRejected { jdbcTemplate.update("insert into companion (id, member_id, voice) values (4, -1, 'LOUD')") }
    }

    @Test
    fun `FR-016 AI 회원에게 직군이나 이메일이나 운영자 역할을 줄 수 없다`() {
        val ai = MemberRow("AI회원", kind = "AI", authMethod = "NONE")

        assertRejected { insert(ai.copy(nickname = "직군있는", jobRole = "DEVELOPMENT", careerYear = "YEAR_1")) }
        assertRejected { insert(ai.copy(nickname = "이메일있는", email = "ai@example.com")) }
        assertRejected { insert(ai.copy(nickname = "로그인되는", authMethod = "GOOGLE")) }
        assertRejected { insert(ai.copy(nickname = "수단없는사람", kind = "HUMAN")) }
        assertRejected { insert(ai.copy(nickname = "온보딩전", onboarded = false)) }
        assertRejected { insert(ai.copy(nickname = "운영자", role = "OPERATOR")) }
        assertRejected { insert(ai.copy(nickname = "모르는종류", kind = "BOT")) }
    }

    @Test
    fun `사람 회원의 온보딩 제약은 그대로다`() {
        val suffix = (System.nanoTime() % 100_000).toString()
        val human = MemberRow("사람$suffix", kind = "HUMAN", authMethod = "GOOGLE")

        insert(human.copy(jobRole = "HR", careerYear = "YEAR_2"))
        insert(human.copy(nickname = null, onboarded = false))

        assertRejected { insert(human.copy(nickname = "직군없$suffix")) }
        val kind =
            jdbcTemplate.queryForObject(
                "select column_default from information_schema.columns " +
                    "where table_name = 'member' and column_name = 'kind'",
                String::class.java,
            )
        assertThat(kind).contains("HUMAN")
    }

    @Test
    fun `FR-002 한 글의 반응 기록은 하나다`() {
        val post = -System.nanoTime()

        insert(ReactionRow(post))

        assertRejected { insert(ReactionRow(post)) }
        assertThat(indexDef("companion_reaction_post_key")).contains("UNIQUE").contains("(post_id)")
        assertThat(indexDef("companion_reaction_due_idx")).contains("(post_created_at)").contains("'WAITING'")
        // 가장 오래 쉰 친구는 친구별 반응으로 고른다(012)
        assertThat(indexDef("companion_reaction_entry_companion_idx")).contains("(companion_id, id DESC)")
    }

    @Test
    fun `US5-AC5 상태와 어긋난 반응 기록은 거절한다`() {
        val post = -System.nanoTime()

        val waiting = ReactionRow(post)
        val closed = ReactionRow(post, next = null, completed = "now()")

        assertRejected { insert(closed.copy(postId = post - 1, status = "SKIPPED", skipReason = null)) }
        assertRejected { insert(waiting.copy(postId = post - 2, skipReason = "RISK")) }
        assertRejected { insert(closed.copy(postId = post - 3, status = "DONE", commentId = null)) }
        assertRejected { insert(waiting.copy(postId = post - 4, next = null)) }
        assertRejected { insert(waiting.copy(postId = post - 5, completed = "now()")) }
        assertRejected { insert(closed.copy(postId = post - 6, status = "SKIPPED", skipReason = "BORED")) }
        assertRejected { insert(waiting.copy(postId = post - 7, status = "REACTING")) }
        insert(closed.copy(postId = post - 8, status = "DONE", commentId = 1, doneCount = 1))
        for ((index, reason) in SKIP_REASONS.withIndex()) {
            insert(closed.copy(postId = post - 10 - index, status = "SKIPPED", skipReason = reason))
        }
    }

    @Test
    fun `US1-AC10 수를 적지 않은 반응 기록은 한 명이 반응하는 것으로 본다`() {
        val post = -System.nanoTime()

        jdbcTemplate.update(
            "insert into companion_reaction (post_id, post_created_at, status, next_attempt_at, created_at) " +
                "values (?, now(), 'WAITING', now(), now())",
            post,
        )

        val sql = "select target_count, done_count from companion_reaction where post_id = ?"
        val row = jdbcTemplate.queryForMap(sql, post)
        assertThat((row["target_count"] as Number).toInt()).isEqualTo(1)
        assertThat((row["done_count"] as Number).toInt()).isZero()
    }

    @Test
    fun `FR-005 반응할 수는 1에서 3이고 반응한 수는 그 수를 넘지 못한다`() {
        val post = -System.nanoTime()
        val waiting = ReactionRow(post)

        assertRejected { insert(waiting.copy(postId = post - 1, targetCount = 0)) }
        assertRejected { insert(waiting.copy(postId = post - 2, targetCount = 4)) }
        assertRejected { insert(waiting.copy(postId = post - 3, targetCount = 2, doneCount = 3)) }
        assertRejected { insert(waiting.copy(postId = post - 4, doneCount = -1)) }
        insert(waiting.copy(postId = post - 5, targetCount = 3, doneCount = 2))
    }

    @Test
    fun `US7-AC4 DONE은 한 명 이상 반응한 것이고 SKIPPED는 아무도 반응하지 않은 것이다`() {
        val post = -System.nanoTime()
        val closed = ReactionRow(post, next = null, completed = "now()", targetCount = 3)

        assertRejected { insert(closed.copy(postId = post - 1, status = "DONE", doneCount = 0)) }
        val skipped = closed.copy(status = "SKIPPED", skipReason = "GONE")
        assertRejected { insert(skipped.copy(postId = post - 2, doneCount = 1)) }
        // 한 명 이상 반응한 뒤 멈췄으면 DONE이고 멈춘 까닭이 남는다
        insert(closed.copy(postId = post - 3, status = "DONE", skipReason = "HUMAN_COMMENT", doneCount = 1))
        // 정해진 수를 다 채웠으면 까닭이 없다
        insert(closed.copy(postId = post - 4, status = "DONE", doneCount = 3))
    }

    @Test
    fun `FR-005 한 글에 같은 친구의 반응은 하나만 적힌다`() {
        val post = -System.nanoTime()
        insert(ReactionRow(post, targetCount = 3))
        val reactionId =
            jdbcTemplate.queryForObject("select id from companion_reaction where post_id = ?", Long::class.java, post)
        val entry =
            "insert into companion_reaction_entry (reaction_id, post_id, companion_id, comment_id, created_at) " +
                "values (?, ?, ?, ?, now())"

        jdbcTemplate.update(entry, reactionId, post, 1, 100)

        assertRejected { jdbcTemplate.update(entry, reactionId, post, 1, 101) }
        jdbcTemplate.update(entry, reactionId, post, 2, 102)
        assertRejected { jdbcTemplate.update(entry, -1, post, 3, 103) }
        assertThat(indexDef("companion_reaction_entry_post_companion_key"))
            .contains("UNIQUE")
            .contains("(post_id, companion_id)")
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

    private fun insert(row: MemberRow) {
        jdbcTemplate.update(
            "insert into member (kind, auth_method, email, nickname, nickname_key, job_role, career_year, " +
                "onboarded_at, role, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, " +
                "${if (row.onboarded) "now()" else "null"}, ?, now(), now())",
            row.kind,
            row.authMethod,
            row.email,
            row.nickname,
            row.nickname,
            row.jobRole,
            row.careerYear,
            row.role,
        )
    }

    private fun insert(row: ReactionRow) {
        jdbcTemplate.update(
            "insert into companion_reaction (post_id, post_created_at, status, skip_reason, comment_id, " +
                "next_attempt_at, created_at, completed_at, target_count, done_count) " +
                "values (?, now(), ?, ?, ?, ${row.next ?: "null"}, now(), ${row.completed ?: "null"}, ?, ?)",
            row.postId,
            row.status,
            row.skipReason,
            row.commentId,
            row.targetCount,
            row.doneCount,
        )
    }

    /** 넣어 볼 회원 한 행. [onboarded]면 `onboarded_at`을 지금으로 넣는다. */
    private data class MemberRow(
        val nickname: String?,
        val kind: String,
        val authMethod: String,
        val email: String? = null,
        val jobRole: String? = null,
        val careerYear: String? = null,
        val onboarded: Boolean = true,
        val role: String = "MEMBER",
    )

    /** 넣어 볼 반응 기록 한 행. [next]와 [completed]는 SQL 식(`now()`)이거나 null이다. */
    private data class ReactionRow(
        val postId: Long,
        val status: String = "WAITING",
        val skipReason: String? = null,
        val commentId: Long? = null,
        val next: String? = "now()",
        val completed: String? = null,
        val targetCount: Int = 1,
        val doneCount: Int = 0,
    )

    private fun indexDef(name: String): String =
        jdbcTemplate.queryForObject("select indexdef from pg_indexes where indexname = ?", String::class.java, name)!!

    companion object {
        private val SKIP_REASONS =
            listOf("HUMAN_COMMENT", "RISK", "GONE", "EXPIRED", "DAILY_LIMIT", "FAILED", "REJECTED")
    }
}
