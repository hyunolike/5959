package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

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

    private fun insertMember(
        authMethod: String,
        email: String,
    ) {
        val passwordHash = if (authMethod == "EMAIL") "'{bcrypt}hash'" else "null"
        jdbcTemplate.update(
            """
            insert into member (auth_method, email, password_hash, created_at, updated_at)
            values ('$authMethod', '$email', $passwordHash, now(), now())
            """.trimIndent(),
        )
    }
}
