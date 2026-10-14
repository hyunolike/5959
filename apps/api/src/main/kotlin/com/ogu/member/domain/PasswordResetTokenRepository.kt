package com.ogu.member.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/**
 * 비밀번호 재설정 링크 `password_reset_token`(015 research R2). 회원마다 행이 하나다. 링크의 값은 저장하지 않고
 * SHA-256(16진수)만 둔다.
 */
@Repository
class PasswordResetTokenRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 회원의 링크를 새로 만든다. 앞의 것이 있으면 덮어써 쓸 수 없게 한다(FR-004). */
    fun replace(
        memberId: Long,
        tokenHash: String,
        now: Instant,
        expiresAt: Instant,
    ) {
        jdbcClient
            .sql(
                """
                insert into password_reset_token (member_id, token_hash, created_at, expires_at, used_at)
                values (:memberId, :tokenHash, :now, :expiresAt, null)
                on conflict (member_id) do update
                  set token_hash = excluded.token_hash, created_at = excluded.created_at,
                      expires_at = excluded.expires_at, used_at = null
                """.trimIndent(),
            ).param("memberId", memberId)
            .param("tokenHash", tokenHash)
            .param("now", Timestamp.from(now))
            .param("expiresAt", Timestamp.from(expiresAt))
            .update()
    }

    /**
     * 링크를 쓴다. 쓰지 않았고 만료되지 않은 것일 때만 회원 번호를 돌려준다. 한 문장이라 겹친 두 요청 가운데 하나만
     * 받는다(US2-AC10). 없거나, 만료됐거나, 이미 썼거나, 새 요청에 밀려났으면 null이다.
     */
    fun consume(
        tokenHash: String,
        now: Instant,
    ): Long? =
        jdbcClient
            .sql(
                """
                update password_reset_token set used_at = :now
                where token_hash = :tokenHash and used_at is null and expires_at > :now
                returning member_id
                """.trimIndent(),
            ).param("tokenHash", tokenHash)
            .param("now", Timestamp.from(now))
            .query(Long::class.java)
            .optional()
            .orElse(null)

    /** 회원의 링크를 지운다. 탈퇴할 때 쓴다(FR-018). */
    fun deleteByMember(memberId: Long): Int =
        jdbcClient
            .sql("delete from password_reset_token where member_id = :memberId")
            .param("memberId", memberId)
            .update()

    /** 만료 시각이 [before]보다 이른 행을 지우고 지운 수를 돌려준다. */
    fun deleteExpiredBefore(before: Instant): Int =
        jdbcClient
            .sql("delete from password_reset_token where expires_at < :before")
            .param("before", Timestamp.from(before))
            .update()
}
