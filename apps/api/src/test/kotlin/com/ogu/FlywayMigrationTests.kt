package com.ogu

import org.assertj.core.api.Assertions.assertThat
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
}
