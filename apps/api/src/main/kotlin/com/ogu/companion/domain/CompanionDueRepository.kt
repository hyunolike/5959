package com.ogu.companion.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant

/**
 * AI 친구의 타이머가 다음에 깰 시각을 읽는 쿼리들(018 research R3). 할 일이 없으면 null이다. 타이머는 이 시각에만 깨어
 * DB를 연다.
 */
@Repository
class CompanionDueRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 반응의 가장 이른 차례: 기다리는 기록마다 올라온 뒤 [wait]이 지난 때와 다음 차례 가운데 늦은 것의 최솟값이다.
     * [CompanionReactionRepository.lockDue]가 맡는 조건과 같다.
     */
    fun earliestReaction(wait: Duration): Instant? =
        jdbcClient
            .sql(
                """
                select min(greatest(post_created_at + make_interval(secs => :waitSeconds), next_attempt_at))
                from companion_reaction where status = 'WAITING'
                """.trimIndent(),
            ).param("waitSeconds", wait.toNanos() / NANOS_PER_SECOND)
            .query { rs, _ -> rs.getTimestamp(1)?.toInstant() }
            .list()
            .firstOrNull()

    /** 쓰는 중인 글 기록의 다음 차례. [CompanionPostRepository.lockWriting]이 맡는 조건과 같다. */
    fun earliestWriting(): Instant? =
        jdbcClient
            .sql("select min(next_attempt_at) from companion_post where status = 'WRITING'")
            .query { rs, _ -> rs.getTimestamp(1)?.toInstant() }
            .list()
            .firstOrNull()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
