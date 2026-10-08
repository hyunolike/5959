package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** V5__safety.sql(005-safety)의 스키마와 제약. V1~V4는 [FlywayMigrationTests]가 본다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SafetyMigrationTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V5 마이그레이션이 숨김과 단계 열, 운영자 역할, safety 테이블과 인덱스를 만든다`() {
        val columns =
            jdbcTemplate.queryForList(
                "select table_name || '.' || column_name from information_schema.columns " +
                    "where table_schema = 'public' " +
                    "and column_name in ('hidden_at', 'hidden_reason', 'risk_level', 'review_requested_at', 'role')",
                String::class.java,
            )
        assertThat(columns).containsExactlyInAnyOrder(
            "posts.hidden_at",
            "posts.hidden_reason",
            "posts.risk_level",
            "posts.review_requested_at",
            "comments.hidden_at",
            "comments.hidden_reason",
            "comments.risk_level",
            "comments.review_requested_at",
            "member.role",
        )

        val tableNames =
            jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' and table_name in " +
                    "('risk_assessment', 'report', 'review_request', 'moderation_action', 'safety_term', " +
                    "'support_resource', 'safety_backfill')",
                String::class.java,
            )
        assertThat(tableNames).hasSize(7)

        // 피드 인덱스는 보이는 글(지우지 않았고 숨기지 않은 글)만 담는다. 내 글 인덱스는 숨긴 글도 담는다
        listOf("posts_feed_latest_idx", "posts_feed_popular_idx", "posts_feed_author_profile_idx").forEach {
            assertThat(indexDef(it)).contains("deleted_at IS NULL").contains("hidden_at IS NULL")
        }
        assertThat(indexDef("posts_author_live_idx")).doesNotContain("hidden_at")
        assertThat(indexDef("risk_assessment_pending_idx")).contains("(next_attempt_at)").contains("'PENDING'")
        assertThat(indexDef("risk_assessment_level_idx")).contains("(level, id DESC)").contains("'NONE'")
        assertThat(indexDef("report_open_target_idx")).contains("(target_type, target_id)").contains("'PENDING'")
    }

    @Test
    fun `V5 시드에 도움 리소스 셋과 종류별 낱말이 있다`() {
        val phones =
            jdbcTemplate.queryForList(
                "select phone from support_resource where active order by display_order",
                String::class.java,
            )
        assertThat(phones).containsExactly("109", "1577-0199", "1388")
        val kinds = jdbcTemplate.queryForList("select distinct kind from safety_term", String::class.java)
        assertThat(kinds).containsExactlyInAnyOrder("CRISIS", "CONCERN", "PROFANITY", "ALLOW")
        // 낱말은 정규화한 형태(공백 없음)로 들어 있다
        val spaced = jdbcTemplate.queryForObject("select count(*) from safety_term where term ~ '\\s'", Int::class.java)
        assertThat(spaced).isZero()
        val backfill = jdbcTemplate.queryForList("select target_type from safety_backfill", String::class.java)
        assertThat(backfill).containsExactlyInAnyOrder("POST", "COMMENT")
    }

    @Test
    fun `숨긴 시각과 이유는 함께 있어야 하고 이유와 단계는 목록 안의 값이어야 한다`() {
        val postId = insertPost()

        assertThatThrownBy { jdbcTemplate.update("update posts set hidden_at = now() where id = ?", postId) }
            .hasMessageContaining("posts_hidden_pair_check")
        assertThatThrownBy { jdbcTemplate.update("update posts set hidden_reason = 'RISK' where id = ?", postId) }
            .hasMessageContaining("posts_hidden_pair_check")
        assertThatThrownBy {
            jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'SPAM' where id = ?", postId)
        }.hasMessageContaining("posts_hidden_reason_check")
        assertThatThrownBy { jdbcTemplate.update("update posts set risk_level = 'HIGH' where id = ?", postId) }
            .hasMessageContaining("posts_risk_level_check")

        jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'RISK' where id = ?", postId)
        val level = jdbcTemplate.queryForObject("select risk_level from posts where id = ?", String::class.java, postId)
        assertThat(level).isEqualTo("NONE")
    }

    @Test
    fun `member의 role은 기본이 MEMBER이고 목록 밖의 값은 거부된다`() {
        val memberId = insertMember("EMAIL", "role-${UUID.randomUUID()}@example.com")

        val role = jdbcTemplate.queryForObject("select role from member where id = ?", String::class.java, memberId)
        assertThat(role).isEqualTo("MEMBER")
        jdbcTemplate.update("update member set role = 'OPERATOR' where id = ?", memberId)
        assertThatThrownBy { jdbcTemplate.update("update member set role = 'ADMIN' where id = ?", memberId) }
            .hasMessageContaining("member_role_check")
    }

    @Test
    fun `같은 회원은 같은 대상을 한 번만 신고할 수 있고 사유는 목록 안의 값이어야 한다`() {
        val targetId = nextPostId()
        insertReport(reporterId = 1, targetId = targetId)

        assertThatThrownBy { insertReport(reporterId = 1, targetId = targetId) }
            .hasMessageContaining("report_reporter_target_key")
        // 다른 회원의 신고와 다른 종류의 같은 ID는 허용한다
        insertReport(reporterId = 2, targetId = targetId)
        insertReport(reporterId = 1, targetId = targetId, targetType = "COMMENT")
        assertThatThrownBy { insertReport(reporterId = 3, targetId = targetId, reason = "BORING") }
            .hasMessageContaining("report_reason_check")
    }

    @Test
    fun `재검토 요청은 대상마다 하나다`() {
        val targetId = nextPostId()
        val insert =
            "insert into review_request (target_type, target_id, post_id, requester_id, status, created_at) " +
                "values ('POST', ?, ?, 1, 'PENDING', now())"
        jdbcTemplate.update(insert, targetId, targetId)

        assertThatThrownBy { jdbcTemplate.update(insert, targetId, targetId) }
            .hasMessageContaining("review_request_target_key")
    }

    @Test
    fun `알림 종류에 도움 안내, 다시 공개, 재검토 결과가 더해졌고 멱등 키는 60자까지다`() {
        listOf("SUPPORT_NOTICE", "CONTENT_RESTORED", "REVIEW_KEPT").forEach { type ->
            insertNotification(nextPostId(), type = type, postId = nextPostId(), dedupKey = "K".repeat(60))
        }
        assertThatThrownBy {
            insertNotification(nextPostId(), type = "SUPPORT_NOTICE", postId = nextPostId(), dedupKey = "K".repeat(61))
        }.hasMessageContaining("character varying(60)")
    }

    @Test
    fun `같은 종류의 같은 낱말은 한 번만 들어간다`() {
        val term = "낱말${System.nanoTime()}".take(40)
        val insert = "insert into safety_term (kind, term, created_at, updated_at) values (?, ?, now(), now())"
        jdbcTemplate.update(insert, "PROFANITY", term)

        assertThatThrownBy { jdbcTemplate.update(insert, "PROFANITY", term) }
            .hasMessageContaining("safety_term_kind_term_key")
        jdbcTemplate.update(insert, "ALLOW", term)
        jdbcTemplate.update("delete from safety_term where term = ?", term)
    }

    private fun insertPost(): Long {
        val authorId = insertMember("EMAIL", "post-${UUID.randomUUID()}@example.com")
        return jdbcTemplate.queryForObject(
            """
            insert into posts (author_id, author_job_role, author_career_year, content, comment_tone, created_at,
                               updated_at)
            values (?, 'DEVELOPMENT', 'YEAR_3', '마이그레이션 확인용 글', 'COMFORT_ME', now() - interval '2 hours',
                    now() - interval '2 hours')
            returning id
            """.trimIndent(),
            Long::class.java,
            authorId,
        )!!
    }

    private fun insertReport(
        reporterId: Long,
        targetId: Long,
        targetType: String = "POST",
        reason: String = "ABUSIVE",
    ) {
        jdbcTemplate.update(
            "insert into report (reporter_id, target_type, target_id, post_id, reason, status, created_at) " +
                "values (?, ?, ?, ?, ?, 'PENDING', now())",
            reporterId,
            targetType,
            targetId,
            targetId,
            reason,
        )
    }

    private fun indexDef(name: String): String =
        jdbcTemplate.queryForObject("select indexdef from pg_indexes where indexname = ?", String::class.java, name)!!

    private fun nextPostId(): Long = ID_SEQUENCE.incrementAndGet()

    private fun insertMember(
        authMethod: String,
        email: String,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into member (auth_method, email, password_hash, created_at, updated_at)
            values (?, ?, '{bcrypt}hash', now(), now())
            returning id
            """.trimIndent(),
            Long::class.java,
            authMethod,
            email,
        )!!

    private fun insertNotification(
        receiverId: Long,
        type: String,
        postId: Long,
        dedupKey: String?,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into notification (receiver_id, type, post_id, actor_count, dedup_key, seq, created_at, updated_at)
            values (?, ?, ?, 1, ?, 1, now(), now())
            returning id
            """.trimIndent(),
            Long::class.java,
            receiverId,
            type,
            postId,
            dedupKey,
        )!!

    private companion object {
        // 같은 컨텍스트를 공유하는 다른 테스트와 겹치지 않도록 큰 값에서 시작한다
        val ID_SEQUENCE = AtomicLong(8_000_000_000L + System.nanoTime() % 1_000_000_000L)
    }
}
