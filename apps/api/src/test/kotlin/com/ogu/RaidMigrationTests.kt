package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

/** V6__raid.sql(006-raid)의 스키마와 제약. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RaidMigrationTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V6 마이그레이션이 레이드 테이블과 인덱스, 알림의 보스 열을 만든다`() {
        val tables =
            jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' " +
                    "and table_name in ('raid_boss', 'raid_contribution')",
                String::class.java,
            )
        assertThat(tables).containsExactlyInAnyOrder("raid_boss", "raid_contribution")
        assertThat(indexDef("raid_boss_alive_key")).contains("UNIQUE").contains("'ALIVE'")
        assertThat(indexDef("raid_contribution_member_idx")).contains("(member_id, boss_id)")
        val nullable =
            jdbcTemplate.queryForObject(
                "select is_nullable from information_schema.columns where table_name = 'notification' " +
                    "and column_name = 'post_id'",
                String::class.java,
            )
        assertThat(nullable).isEqualTo("YES")
    }

    @Test
    fun `US5-AC6 살아 있는 보스는 하나만 넣을 수 있다`() {
        jdbcTemplate.update("update raid_boss set status = 'RETREATED', ended_at = now() where status = 'ALIVE'")
        insertBoss("ALIVE", hp = 10, ended = false)

        assertRejected { insertBoss("ALIVE", hp = 10, ended = false) }
        // 끝난 보스는 여럿이어도 된다
        insertBoss("DEFEATED", hp = 0, ended = true)
        insertBoss("RETREATED", hp = 3, ended = true)
    }

    @Test
    fun `보스의 상태와 HP, 끝난 때가 서로 맞지 않으면 거절한다`() {
        // 처치됐는데 HP가 남았다, 끝났는데 끝난 때가 없다, HP가 최대를 넘는다
        assertRejected { insertBoss("DEFEATED", hp = 1, ended = true) }
        assertRejected { insertBoss("RETREATED", hp = 1, ended = false) }
        assertRejected { insertBoss("RETREATED", hp = 11, ended = true) }
        assertRejected { insertBoss("RETREATED", hp = -1, ended = true) }
    }

    @Test
    fun `보스 처치 알림만 글이 없고 보스를 가리킨다`() {
        val receiver = System.nanoTime()

        insertNotification(receiver, "RAID_BOSS_DEFEATED", postId = null, bossId = 1, seq = 1)
        insertNotification(receiver, "POST_COMMENT", postId = 1, bossId = null, seq = 2)

        assertThatThrownBy { insertNotification(receiver, "RAID_BOSS_DEFEATED", postId = 1, bossId = 1, seq = 3) }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { insertNotification(receiver, "RAID_BOSS_DEFEATED", postId = null, bossId = null, seq = 4) }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { insertNotification(receiver, "POST_COMMENT", postId = null, bossId = null, seq = 5) }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { insertNotification(receiver, "POST_COMMENT", postId = 1, bossId = 1, seq = 6) }
            .isInstanceOf(DataAccessException::class.java)
    }

    private fun assertRejected(insert: () -> Unit) {
        assertThatThrownBy { insert() }.isInstanceOf(DataAccessException::class.java)
    }

    private fun insertBoss(
        status: String,
        hp: Int,
        ended: Boolean,
    ) {
        jdbcTemplate.update(
            "insert into raid_boss (emotion, max_hp, hp, status, spawned_at, ended_at) " +
                "values ('ANXIETY', 10, ?, ?, now(), ${if (ended) "now()" else "null"})",
            hp,
            status,
        )
    }

    private fun insertNotification(
        receiverId: Long,
        type: String,
        postId: Long?,
        bossId: Long?,
        seq: Long,
    ) {
        jdbcTemplate.update(
            "insert into notification (receiver_id, type, post_id, raid_boss_id, seq, created_at, updated_at) " +
                "values (?, ?, ?, ?, ?, now(), now())",
            receiverId,
            type,
            postId,
            bossId,
            seq,
        )
    }

    private fun indexDef(name: String): String =
        jdbcTemplate.queryForObject("select indexdef from pg_indexes where indexname = ?", String::class.java, name)!!
}
