package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** V13__member_withdrawal.sql(013-member-withdrawal)의 스키마와 제약. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WithdrawalMigrationTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V13 마이그레이션이 탈퇴한 때의 열과 댓글 색인을 만든다`() {
        val nullable =
            jdbcTemplate.queryForObject(
                """
                select is_nullable from information_schema.columns
                where table_name = 'member' and column_name = 'withdrawn_at'
                """.trimIndent(),
                String::class.java,
            )
        assertThat(nullable).isEqualTo("YES")
        val index =
            jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where indexname = 'comments_author_id_idx'",
                String::class.java,
            )
        assertThat(index).contains("(author_id, id)")
    }

    @Test
    fun `탈퇴한 행에 개인 정보가 남아 있으면 거절한다`() {
        val id = emailMember()

        assertRejected { jdbcTemplate.update("update member set withdrawn_at = now() where id = ?", id) }
        assertRejected {
            jdbcTemplate.update("update member set withdrawn_at = now(), email = null where id = ?", id)
        }
        jdbcTemplate.update(
            "update member set withdrawn_at = now(), email = null, password_hash = null where id = ?",
            id,
        )
    }

    @Test
    fun `이메일 가입 회원은 탈퇴하지 않았으면 이메일과 비밀번호가 있어야 한다`() {
        val id = emailMember()

        assertRejected { jdbcTemplate.update("update member set email = null where id = ?", id) }
    }

    @Test
    fun `AI 친구와 운영자는 탈퇴한 행이 될 수 없다`() {
        val aiId = jdbcTemplate.queryForObject("select min(id) from member where kind = 'AI'", Long::class.java)
        val operatorId = emailMember()
        jdbcTemplate.update("update member set role = 'OPERATOR' where id = ?", operatorId)

        assertRejected {
            jdbcTemplate.update(
                "update member set withdrawn_at = now(), nickname = null, nickname_key = null, onboarded_at = null " +
                    "where id = ?",
                aiId,
            )
        }
        assertRejected {
            jdbcTemplate.update(
                "update member set withdrawn_at = now(), email = null, password_hash = null where id = ?",
                operatorId,
            )
        }
    }

    /** 온보딩 전의 이메일 가입 회원 한 명을 넣고 번호를 돌려준다. */
    private fun emailMember(): Long =
        jdbcTemplate.queryForObject(
            """
            insert into member (auth_method, email, password_hash, created_at, updated_at)
            values ('EMAIL', ?, '{bcrypt}x', now(), now())
            returning id
            """.trimIndent(),
            Long::class.java,
            "migration-${UUID.randomUUID()}@example.com",
        )!!

    private fun assertRejected(statement: () -> Unit) {
        assertThatThrownBy { statement() }.isInstanceOf(DataAccessException::class.java)
    }
}
