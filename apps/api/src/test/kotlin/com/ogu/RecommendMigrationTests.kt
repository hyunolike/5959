package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

/** V7__recommend.sql(007-recommend)의 스키마와 제약. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RecommendMigrationTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V7 마이그레이션이 임베딩 테이블과 색인을 만든다`() {
        val type =
            jdbcTemplate.queryForObject(
                "select format_type(atttypid, atttypmod) from pg_attribute " +
                    "where attrelid = 'post_embedding'::regclass and attname = 'embedding'",
                String::class.java,
            )
        assertThat(type).isEqualTo("halfvec(2048)")
        assertThat(indexDef("post_embedding_hnsw_idx")).contains("hnsw").contains("halfvec_cosine_ops")
        assertThat(indexDef("post_embedding_pending_idx")).contains("(next_attempt_at)").contains("'PENDING'")
        assertThat(indexDef("emotion_analysis_emotion_idx")).contains("(emotion, post_id DESC)")
    }

    @Test
    fun `값 없이 DONE이거나 모델 없이 값만 있거나 모르는 상태면 거절한다`() {
        val id = System.nanoTime()
        val vector = (1..2048).joinToString(prefix = "[", postfix = "]", separator = ",") { "0.1" }

        assertRejected { insert(id + 1, "DONE", embedding = null, model = null) }
        assertRejected { insert(id + 2, "PENDING", embedding = vector, model = null) }
        assertRejected { insert(id + 3, "PENDING", embedding = null, model = "m") }
        assertRejected { insert(id + 4, "WAITING", embedding = null, model = null) }
        // 차원이 다른 값은 넣을 수 없다
        assertRejected { insert(id + 5, "DONE", embedding = "[0.1,0.2,0.3]", model = "m") }
        insert(id + 6, "DONE", embedding = vector, model = "m")
        insert(id + 7, "PENDING", embedding = null, model = null)
    }

    private fun assertRejected(insert: () -> Unit) {
        assertThatThrownBy { insert() }.isInstanceOf(DataAccessException::class.java)
    }

    private fun insert(
        postId: Long,
        status: String,
        embedding: String?,
        model: String?,
    ) {
        jdbcTemplate.update(
            "insert into post_embedding " +
                "(post_id, author_id, status, embedding, model, next_attempt_at, requested_at) " +
                "values (?, 1, ?, cast(? as halfvec), ?, now() + interval '1 day', now())",
            postId,
            status,
            embedding,
            model,
        )
    }

    private fun indexDef(name: String): String =
        jdbcTemplate.queryForObject("select indexdef from pg_indexes where indexname = ?", String::class.java, name)!!
}
