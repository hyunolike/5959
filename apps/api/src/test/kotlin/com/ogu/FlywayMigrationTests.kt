package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class FlywayMigrationTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V1 마이그레이션이 pgvector 확장을 설치한다`() {
        val count =
            jdbcTemplate.queryForObject(
                "select count(*) from pg_extension where extname = 'vector'",
                Int::class.java,
            )
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `V1 마이그레이션이 이벤트 발행 테이블을 만든다`() {
        val count =
            jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'event_publication'",
                Int::class.java,
            )
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `V2 마이그레이션이 회원, 외부 계정, 세션, 로그인 실패 기록 테이블을 만든다`() {
        val tableNames =
            jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_name in " +
                    "('member', 'oauth_identity', 'auth_session', 'login_attempt')",
                String::class.java,
            )
        assertThat(tableNames)
            .containsExactlyInAnyOrder("member", "oauth_identity", "auth_session", "login_attempt")
    }

    @Test
    fun `member의 이메일 부분 유일 인덱스는 EMAIL 가입 행끼리만 중복을 막는다`() {
        insertMember(authMethod = "EMAIL", email = "same@ogu.dev")

        assertThatThrownBy {
            insertMember(authMethod = "EMAIL", email = "same@ogu.dev")
        }.hasMessageContaining("member_email_key")

        // 외부 계정끼리는 같은 이메일이어도 허용된다
        insertMember(authMethod = "KAKAO", email = "same@ogu.dev")
        insertMember(authMethod = "GOOGLE", email = "same@ogu.dev")
    }

    @Test
    fun `onboarded_at이 있는데 nickname이 NULL이면 체크 제약으로 거부된다`() {
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                insert into member (auth_method, email, password_hash, nickname, nickname_key,
                                     job_role, career_year, onboarded_at, created_at, updated_at)
                values ('EMAIL', 'onboarded@ogu.dev', '{bcrypt}hash', null, null,
                        'DEVELOPMENT', 'YEAR_1', now(), now(), now())
                """.trimIndent(),
            )
        }.hasMessageContaining("member_onboarding_fields_required_check")
    }

    @Test
    fun `V3 마이그레이션이 글, 댓글, 공감, 감정 분석, 몬스터, HP 기록 테이블을 만든다`() {
        val tableNames =
            jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' and table_name in " +
                    "('posts', 'comments', 'post_likes', 'comment_likes', 'emotion_analysis', 'monsters', " +
                    "'monster_hp_log')",
                String::class.java,
            )
        assertThat(tableNames).containsExactlyInAnyOrder(
            "posts",
            "comments",
            "post_likes",
            "comment_likes",
            "emotion_analysis",
            "monsters",
            "monster_hp_log",
        )
    }

    @Test
    fun `monster_hp_log 유일 키는 같은 회원의 같은 공격을 두 번 기록하지 않는다`() {
        val monsterId = insertMonster(maxHp = 10, hp = 10, status = "ALIVE")
        val memberId = insertMember(authMethod = "KAKAO", email = "attacker@ogu.dev")
        insertHpLog(monsterId, memberId, action = "POST_LIKE", targetId = 1L)

        assertThatThrownBy {
            insertHpLog(monsterId, memberId, action = "POST_LIKE", targetId = 1L)
        }.hasMessageContaining("monster_hp_log_attack_key")

        // 다른 행동은 따로 기록된다
        insertHpLog(monsterId, memberId, action = "COMMENT", targetId = 1L)
    }

    @Test
    fun `monsters는 hp가 max_hp보다 크면 체크 제약으로 거부된다`() {
        assertThatThrownBy {
            insertMonster(maxHp = 10, hp = 11, status = "ALIVE")
        }.hasMessageContaining("monsters_hp_range_check")
    }

    @Test
    fun `monsters는 DEFEATED인데 hp가 0보다 크면 체크 제약으로 거부된다`() {
        assertThatThrownBy {
            insertMonster(maxHp = 20, hp = 5, status = "DEFEATED")
        }.hasMessageContaining("monsters_defeated_hp_check")
    }

    @Test
    fun `monsters는 ALIVE인데 hp가 0이면 체크 제약으로 거부된다`() {
        assertThatThrownBy {
            insertMonster(maxHp = 20, hp = 0, status = "ALIVE")
        }.hasMessageContaining("monsters_defeated_hp_check")
    }

    @Test
    fun `monsters의 max_hp는 10, 20, 30만 허용한다`() {
        assertThatThrownBy {
            insertMonster(maxHp = 15, hp = 15, status = "ALIVE")
        }.hasMessageContaining("monsters_max_hp_check")
    }

    @Test
    fun `emotion_analysis는 ANALYZED인데 emotion이 NULL이면 체크 제약으로 거부된다`() {
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                insert into emotion_analysis (post_id, status, emotion, intensity, next_attempt_at, post_created_at)
                values (?, 'ANALYZED', null, 'LOW', now(), now())
                """.trimIndent(),
                nextPostId(),
            )
        }.hasMessageContaining("emotion_analysis_result_required_check")
    }

    @Test
    fun `emotion_analysis는 PENDING이면 emotion 없이 저장된다`() {
        val inserted =
            jdbcTemplate.update(
                """
                insert into emotion_analysis (post_id, status, next_attempt_at, post_created_at)
                values (?, 'PENDING', now(), now())
                """.trimIndent(),
                nextPostId(),
            )
        assertThat(inserted).isEqualTo(1)
    }

    @Test
    fun `posts의 공감 수가 음수가 되면 체크 제약으로 거부된다`() {
        val authorId = insertMember(authMethod = "GOOGLE", email = "author@ogu.dev")
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                insert into posts (author_id, author_job_role, author_career_year, content, comment_tone,
                                   like_count, created_at, updated_at)
                values (?, 'DEVELOPMENT', 'YEAR_1', '고민', 'COMFORT_ME', -1, now(), now())
                """.trimIndent(),
                authorId,
            )
        }.hasMessageContaining("posts_like_count_check")
    }

    @Test
    fun `V3 부분 인덱스와 이름 붙인 제약이 있다`() {
        val indexes =
            jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = 'public' and tablename in " +
                    "('posts', 'comments', 'emotion_analysis')",
                String::class.java,
            )
        assertThat(indexes).contains(
            "posts_feed_latest_idx",
            "posts_feed_popular_idx",
            "posts_feed_author_profile_idx",
            "posts_author_created_at_idx",
            "comments_post_parent_id_idx",
            "emotion_analysis_pending_idx",
        )
        val pendingIndex =
            jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where indexname = 'emotion_analysis_pending_idx'",
                String::class.java,
            )
        assertThat(pendingIndex).contains("WHERE ((status)::text = 'PENDING'::text)")

        val constraints =
            jdbcTemplate.queryForList(
                "select conname from pg_constraint where conname in " +
                    "('post_likes_pkey', 'comment_likes_pkey', 'monsters_post_id_key', 'monster_hp_log_attack_key')",
                String::class.java,
            )
        assertThat(constraints).containsExactlyInAnyOrder(
            "post_likes_pkey",
            "comment_likes_pkey",
            "monsters_post_id_key",
            "monster_hp_log_attack_key",
        )
    }

    private fun nextPostId(): Long = POST_ID_SEQUENCE.incrementAndGet()

    private fun insertMonster(
        maxHp: Int,
        hp: Int,
        status: String,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into monsters (post_id, emotion, max_hp, hp, status, created_at)
            values (?, 'ANXIETY', ?, ?, ?, now())
            returning id
            """.trimIndent(),
            Long::class.java,
            nextPostId(),
            maxHp,
            hp,
            status,
        )!!

    private fun insertHpLog(
        monsterId: Long,
        memberId: Long,
        action: String,
        targetId: Long,
    ) {
        jdbcTemplate.update(
            """
            insert into monster_hp_log (monster_id, member_id, action, target_id, hp_delta, hp_before, hp_after,
                                        created_at)
            values (?, ?, ?, ?, 1, 10, 9, now())
            """.trimIndent(),
            monsterId,
            memberId,
            action,
            targetId,
        )
    }

    private fun insertMember(
        authMethod: String,
        email: String,
    ): Long {
        val passwordHash = if (authMethod == "EMAIL") "'{bcrypt}hash'" else "null"
        return jdbcTemplate.queryForObject(
            """
            insert into member (auth_method, email, password_hash, created_at, updated_at)
            values ('$authMethod', '$email', $passwordHash, now(), now())
            returning id
            """.trimIndent(),
            Long::class.java,
        )!!
    }

    companion object {
        // 같은 컨텍스트를 공유하는 다른 테스트와 겹치지 않도록 큰 값에서 시작한다
        private val POST_ID_SEQUENCE = AtomicLong(9_000_000_000L + System.nanoTime() % 1_000_000_000L)
    }
}
