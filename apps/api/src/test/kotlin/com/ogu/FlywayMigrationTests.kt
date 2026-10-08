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

    @Test
    fun `V4 마이그레이션이 알림, 전달 기록, 공감 묶음 참여자, 연결 표 테이블과 인덱스를 만든다`() {
        val tableNames =
            jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' and table_name in " +
                    "('notification', 'notification_sequence', 'like_notification_participant', 'sse_ticket')",
                String::class.java,
            )
        assertThat(tableNames).containsExactlyInAnyOrder(
            "notification",
            "notification_sequence",
            "like_notification_participant",
            "sse_ticket",
        )

        val indexes =
            jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = 'public'",
                String::class.java,
            )
        assertThat(indexes).contains(
            "posts_author_live_idx",
            "comments_author_live_idx",
            "post_likes_member_created_idx",
            "monster_hp_log_member_monster_idx",
            "notification_unread_like_group_key",
            "notification_unread_idx",
            "notification_created_at_idx",
            "like_notification_participant_notification_id_idx",
            "sse_ticket_expires_at_idx",
        )
        assertThat(indexDef("posts_author_live_idx"))
            .contains("(author_id, id DESC)")
            .contains("WHERE (deleted_at IS NULL)")
        assertThat(indexDef("comments_author_live_idx"))
            .contains("(author_id, id DESC)")
            .contains("WHERE (deleted_at IS NULL)")
        assertThat(indexDef("post_likes_member_created_idx")).contains("(member_id, created_at DESC, post_id DESC)")
        assertThat(indexDef("monster_hp_log_member_monster_idx")).contains("(member_id, monster_id)")
        assertThat(indexDef("notification_unread_like_group_key"))
            .startsWith("CREATE UNIQUE INDEX")
            .contains("(receiver_id, post_id)")
            .contains("'POST_LIKE'")
            .contains("read_at IS NULL")
        assertThat(indexDef("notification_unread_idx")).contains("(receiver_id)").contains("WHERE (read_at IS NULL)")
    }

    @Test
    fun `V4 제약에는 모두 이름이 붙어 있다`() {
        val constraints =
            jdbcTemplate.queryForList(
                "select conname from pg_constraint where conname in " +
                    "('notification_receiver_seq_key', 'notification_receiver_dedup_key', " +
                    "'notification_sequence_pkey', 'like_notification_participant_pkey', " +
                    "'like_notification_participant_notification_id_fkey', 'sse_ticket_pkey', " +
                    "'sse_ticket_member_id_fkey', 'sse_ticket_expiry_check')",
                String::class.java,
            )
        assertThat(constraints).containsExactlyInAnyOrder(
            "notification_receiver_seq_key",
            "notification_receiver_dedup_key",
            "notification_sequence_pkey",
            "like_notification_participant_pkey",
            "like_notification_participant_notification_id_fkey",
            "sse_ticket_pkey",
            "sse_ticket_member_id_fkey",
            "sse_ticket_expiry_check",
        )
    }

    @Test
    fun `안 읽은 공감 묶음은 같은 받는 사람과 글에 하나뿐이고, 읽은 뒤에는 새 묶음을 허용한다`() {
        val receiverId = nextPostId()
        val postId = nextPostId()
        val first = insertNotification(receiverId, type = "POST_LIKE", postId = postId, dedupKey = null)

        assertThatThrownBy {
            insertNotification(receiverId, type = "POST_LIKE", postId = postId, dedupKey = null)
        }.hasMessageContaining("notification_unread_like_group_key")

        jdbcTemplate.update("update notification set read_at = now() where id = ?", first)
        insertNotification(receiverId, type = "POST_LIKE", postId = postId, dedupKey = null)
    }

    @Test
    fun `안 읽은 공감 묶음 유일 인덱스는 같은 조건식을 되풀이한 ON CONFLICT로 쓸 수 있다`() {
        val receiverId = nextPostId()
        val postId = nextPostId()
        insertNotification(receiverId, type = "POST_LIKE", postId = postId, dedupKey = null)

        val updated =
            jdbcTemplate.update(
                """
                insert into notification (receiver_id, type, post_id, latest_actor_id, seq, created_at, updated_at)
                values (?, 'POST_LIKE', ?, 7, ?, now(), now())
                on conflict (receiver_id, post_id) where type = 'POST_LIKE' and read_at is null
                do update set actor_count = notification.actor_count + 1
                """.trimIndent(),
                receiverId,
                postId,
                SEQ.incrementAndGet(),
            )

        assertThat(updated).isEqualTo(1)
        val actorCount =
            jdbcTemplate.queryForObject(
                "select actor_count from notification where receiver_id = ? and post_id = ?",
                Int::class.java,
                receiverId,
                postId,
            )
        assertThat(actorCount).isEqualTo(2)
    }

    @Test
    fun `같은 받는 사람에게 같은 멱등 키의 알림은 두 번 들어가지 않는다`() {
        val receiverId = nextPostId()
        insertNotification(receiverId, type = "POST_COMMENT", postId = nextPostId(), dedupKey = "COMMENT:1")

        assertThatThrownBy {
            insertNotification(receiverId, type = "POST_REPLY", postId = nextPostId(), dedupKey = "COMMENT:1")
        }.hasMessageContaining("notification_receiver_dedup_key")
    }

    @Test
    fun `같은 받는 사람의 seq는 겹칠 수 없다`() {
        val receiverId = nextPostId()
        insertNotification(receiverId, type = "POST_COMMENT", postId = 1L, dedupKey = "COMMENT:1", seq = 1L)

        assertThatThrownBy {
            insertNotification(receiverId, type = "POST_COMMENT", postId = 1L, dedupKey = "COMMENT:2", seq = 1L)
        }.hasMessageContaining("notification_receiver_seq_key")
    }

    @Test
    fun `공감 묶음이 아닌 알림의 actor_count가 1이 아니면 체크 제약으로 거부된다`() {
        assertThatThrownBy {
            insertNotification(
                nextPostId(),
                type = "POST_COMMENT",
                postId = nextPostId(),
                dedupKey = "COMMENT:9",
                actorCount = 2,
            )
        }.hasMessageContaining("notification_actor_count_like_only_check")
    }

    @Test
    fun `actor_count가 0이면 체크 제약으로 거부된다`() {
        assertThatThrownBy {
            insertNotification(nextPostId(), type = "POST_LIKE", postId = nextPostId(), dedupKey = null, actorCount = 0)
        }.hasMessageContaining("notification_actor_count_check")
    }

    @Test
    fun `공감 묶음에 멱등 키가 있으면 체크 제약으로 거부된다`() {
        assertThatThrownBy {
            insertNotification(nextPostId(), type = "POST_LIKE", postId = nextPostId(), dedupKey = "LIKE:1")
        }.hasMessageContaining("notification_like_dedup_key_check")
    }

    @Test
    fun `알림 종류 7종 밖의 type은 체크 제약으로 거부된다`() {
        assertThatThrownBy {
            insertNotification(nextPostId(), type = "COMMENT_LIKE", postId = nextPostId(), dedupKey = "X:1")
        }.hasMessageContaining("notification_type_check")
    }

    @Test
    fun `알림을 지우면 공감 묶음 참여자 행이 함께 지워진다`() {
        val postId = nextPostId()
        val notificationId = insertNotification(nextPostId(), type = "POST_LIKE", postId = postId, dedupKey = null)
        jdbcTemplate.update(
            "insert into like_notification_participant (post_id, liker_id, notification_id, created_at) " +
                "values (?, 7, ?, now())",
            postId,
            notificationId,
        )

        jdbcTemplate.update("delete from notification where id = ?", notificationId)

        val left =
            jdbcTemplate.queryForObject(
                "select count(*) from like_notification_participant where post_id = ?",
                Int::class.java,
                postId,
            )
        assertThat(left).isZero()
    }

    @Test
    fun `공감 묶음 참여자의 notification_id가 NULL이면 거부된다`() {
        assertThatThrownBy {
            jdbcTemplate.update(
                "insert into like_notification_participant (post_id, liker_id, notification_id, created_at) " +
                    "values (?, 7, null, now())",
                nextPostId(),
            )
        }.hasMessageContaining("null value in column \"notification_id\"")
    }

    @Test
    fun `연결 표의 만료 시각이 만든 시각보다 늦지 않으면 체크 제약으로 거부된다`() {
        val memberId = insertMember(authMethod = "KAKAO", email = "ticket@ogu.dev")
        val session = UUID.randomUUID()

        assertThatThrownBy {
            insertTicket(memberId, session, expiresOffsetSeconds = 0)
        }.hasMessageContaining("sse_ticket_expiry_check")
        assertThatThrownBy {
            insertTicket(memberId, session, expiresOffsetSeconds = -1)
        }.hasMessageContaining("sse_ticket_expiry_check")
        insertTicket(memberId, session, expiresOffsetSeconds = 30)
    }

    @Test
    fun `연결 표는 없는 회원을 가리킬 수 없다`() {
        assertThatThrownBy {
            insertTicket(memberId = -1L, sessionId = UUID.randomUUID(), expiresOffsetSeconds = 30)
        }.hasMessageContaining("sse_ticket_member_id_fkey")
    }

    @Test
    fun `알림 전달 기록의 마지막 번호가 음수면 체크 제약으로 거부된다`() {
        assertThatThrownBy {
            jdbcTemplate.update(
                "insert into notification_sequence (member_id, last_seq) values (?, -1)",
                nextPostId(),
            )
        }.hasMessageContaining("notification_sequence_last_seq_check")
    }

    private fun indexDef(name: String): String =
        jdbcTemplate.queryForObject("select indexdef from pg_indexes where indexname = ?", String::class.java, name)!!

    @Suppress("LongParameterList")
    private fun insertNotification(
        receiverId: Long,
        type: String,
        postId: Long,
        dedupKey: String?,
        actorCount: Int = 1,
        seq: Long = SEQ.incrementAndGet(),
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into notification (receiver_id, type, post_id, latest_actor_id, actor_count, dedup_key, seq,
                                      created_at, updated_at)
            values (?, ?, ?, 7, ?, ?, ?, now(), now())
            returning id
            """.trimIndent(),
            Long::class.java,
            receiverId,
            type,
            postId,
            actorCount,
            dedupKey,
            seq,
        )!!

    private fun insertTicket(
        memberId: Long,
        sessionId: UUID,
        expiresOffsetSeconds: Int,
    ) {
        jdbcTemplate.update(
            """
            insert into sse_ticket (token_hash, member_id, session_id, created_at, expires_at)
            values (?, ?, ?, timestamptz '2026-01-01 00:00:00+00',
                    timestamptz '2026-01-01 00:00:00+00' + make_interval(secs => ?))
            """.trimIndent(),
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .padEnd(64, '0'),
            memberId,
            sessionId,
            expiresOffsetSeconds,
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
        private val SEQ = AtomicLong()
    }
}
