package com.ogu.member.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 소비에 성공한 연결 표(004 research R3). 발급한 세션이 아직 유효한지는 부르는 쪽이 확인한다. */
data class ConsumedSseTicket(
    val memberId: Long,
    val sessionId: UUID,
)

/**
 * 연결 표 `sse_ticket`(004 data-model.md). 원문은 저장하지 않고 SHA-256 16진수 해시만 둔다. 모든 쓰기가 한 문장이라
 * 자동 커밋으로 실행한다.
 */
@Repository
class SseTicketRepository(
    private val jdbcClient: JdbcClient,
) {
    fun insert(
        tokenHash: String,
        memberId: Long,
        sessionId: UUID,
        createdAt: Instant,
        expiresAt: Instant,
    ) {
        jdbcClient
            .sql(
                """
                insert into sse_ticket (token_hash, member_id, session_id, created_at, expires_at)
                values (:tokenHash, :memberId, :sessionId, :createdAt, :expiresAt)
                """.trimIndent(),
            ).param("tokenHash", tokenHash)
            .param("memberId", memberId)
            .param("sessionId", sessionId)
            .param("createdAt", Timestamp.from(createdAt))
            .param("expiresAt", Timestamp.from(expiresAt))
            .update()
    }

    /**
     * 한 문장으로 소비한다(research R3). 아직 안 썼고 만료되지 않은 표만 `used_at`을 기록하고 회원과 세션을 돌려준다.
     * 같은 표로 동시에 들어와도 행 잠금이 줄을 세우고, 늦게 온 문장은 바뀐 행에 조건을 다시 적용해 아무것도 받지 못한다.
     * 시각은 앱의 시계([now])로 비교해 발급과 소비가 같은 시계를 본다.
     */
    fun consume(
        tokenHash: String,
        now: Instant,
    ): ConsumedSseTicket? =
        jdbcClient
            .sql(
                """
                update sse_ticket set used_at = :now
                where token_hash = :tokenHash and used_at is null and expires_at > :now
                returning member_id, session_id
                """.trimIndent(),
            ).param("tokenHash", tokenHash)
            .param("now", Timestamp.from(now))
            .query { rs, _ -> ConsumedSseTicket(rs.getLong("member_id"), rs.getObject("session_id", UUID::class.java)) }
            .optional()
            .orElse(null)

    /** 만료 시각이 [before]보다 이른 표를 지우고 지운 행 수를 돌려준다. */
    fun deleteExpiredBefore(before: Instant): Int =
        jdbcClient
            .sql("delete from sse_ticket where expires_at < :before")
            .param("before", Timestamp.from(before))
            .update()
}
