package com.ogu.support

import org.awaitility.Awaitility.await
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID

/** 추천 테스트 도우미(007). 임베딩은 글 저장이 커밋된 뒤 비동기로 만들어지므로 끝나기를 기다려야 한다. */
class RecommendFixture(
    private val jdbcTemplate: JdbcTemplate,
) {
    /** 다른 테스트의 글과 겹치지 않는 주제 표지. 같은 표지끼리만 가깝다. */
    fun topic(): String = "[주제:${UUID.randomUUID().toString().take(8)}]"

    fun row(postId: Long): Map<String, Any?>? =
        jdbcTemplate
            .queryForList(
                """
                select status, embedding is not null as has_embedding, model, requested_seq, embedded_seq, attempts,
                       next_attempt_at, last_error, requested_at
                from post_embedding where post_id = ?
                """.trimIndent(),
                postId,
            ).firstOrNull()

    fun status(postId: Long): String? = row(postId)?.get("status") as String?

    fun awaitStatus(
        postId: Long,
        status: String,
    ) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { status(postId) == status }
    }

    fun awaitEmbedded(vararg postIds: Long) {
        postIds.forEach { awaitStatus(it, "DONE") }
    }

    /** 첫 시도가 끝나(실패해) 시도 횟수가 [attempts]가 될 때까지 기다린다. */
    fun awaitAttempts(
        postId: Long,
        attempts: Int,
    ) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { row(postId)?.get("attempts") == attempts }
    }

    fun awaitGone(postId: Long) {
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { row(postId) == null }
    }

    companion object {
        val AWAIT_LIMIT: Duration = Duration.ofSeconds(10)
        val POLL: Duration = Duration.ofMillis(50)
    }
}
