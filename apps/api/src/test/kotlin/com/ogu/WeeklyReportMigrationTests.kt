package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

/** V8__weekly_report.sql(008-weekly-report)의 스키마와 제약. 회원 번호는 다른 테스트와 겹치지 않게 음수를 쓴다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WeeklyReportMigrationTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V8 마이그레이션이 리포트 테이블과 색인을 만든다`() {
        assertThat(indexDef("weekly_report_member_week_key")).contains("UNIQUE").contains("(member_id, week_start)")
        assertThat(indexDef("weekly_report_letter_pending_idx")).contains("(published_at DESC)").contains("'PENDING'")
        assertThat(indexDef("weekly_report_published_idx")).contains("(published_at)")
        val authors = indexDef("posts_created_author_idx")
        assertThat(authors).contains("(created_at, author_id)").contains("deleted_at IS NULL")
        assertThat(indexDef("post_likes_post_created_idx")).contains("(post_id, created_at)")
        assertThat(indexDef("weekly_report_run_pkey")).contains("(week_start)")
    }

    @Test
    fun `회원과 주마다 리포트는 하나이고 주의 시작은 월요일이어야 한다`() {
        val member = -System.nanoTime()

        insert(member, "2026-10-05")
        insert(member, "2026-10-12")

        assertRejected { insert(member, "2026-10-05") }
        assertRejected { insert(member - 1, "2026-10-06") }
    }

    @Test
    fun `편지와 상태가 어긋난 리포트는 거절한다`() {
        val member = -System.nanoTime()

        assertRejected { insert(member - 1, status = "DONE", letter = null, next = null) }
        assertRejected { insert(member - 2, status = "PENDING", letter = "편지", next = "now()") }
        assertRejected { insert(member - 3, status = "PENDING", letter = null, next = null) }
        assertRejected { insert(member - 4, status = "SUPPORT", letter = null, next = "now()") }
        assertRejected { insert(member - 5, status = "WRITING", letter = null, next = null) }
        insert(member - 7, status = "DONE", letter = "편지", next = null)
        insert(member - 8, status = "SUPPORT", letter = null, next = null)
        insert(member - 9, status = "GIVEN_UP", letter = null, next = null)
    }

    @Test
    fun `리포트 알림은 글 없이 주를 가리키고 다른 알림은 주를 가리키지 않는다`() {
        val receiver = -System.nanoTime()

        notify(receiver, "WEEKLY_REPORT", postId = null, week = "2026-10-05")

        assertRejected { notify(receiver - 1, "WEEKLY_REPORT", postId = null, week = null) }
        assertRejected { notify(receiver - 2, "WEEKLY_REPORT", postId = 1, week = "2026-10-05") }
        assertRejected { notify(receiver - 3, "MONSTER_SPAWNED", postId = 1, week = "2026-10-05") }
    }

    private fun assertRejected(insert: () -> Unit) {
        assertThatThrownBy { insert() }.isInstanceOf(DataAccessException::class.java)
    }

    private fun insert(
        memberId: Long,
        weekStart: String = "2026-10-05",
        status: String = "PENDING",
        letter: String? = null,
        next: String? = "now()",
    ) {
        jdbcTemplate.update(
            "insert into weekly_report (member_id, week_start, post_count, emotion_counts, unanalyzed_count, " +
                "defeated_count, received_likes, received_comments, letter_status, letter, letter_next_attempt_at, " +
                "published_at) values (?, cast(? as date), 1, '{}', 0, 0, 0, 0, ?, ?, ${next ?: "null"}, now())",
            memberId,
            weekStart,
            status,
            letter,
        )
    }

    private fun notify(
        receiverId: Long,
        type: String,
        postId: Long?,
        week: String?,
    ) {
        jdbcTemplate.update(
            "insert into notification (receiver_id, type, post_id, report_week_start, dedup_key, seq, created_at, " +
                "updated_at) values (?, ?, ?, cast(? as date), 'k', 1, now(), now())",
            receiverId,
            type,
            postId,
            week,
        )
    }

    private fun indexDef(name: String): String =
        jdbcTemplate.queryForObject("select indexdef from pg_indexes where indexname = ?", String::class.java, name)!!
}
