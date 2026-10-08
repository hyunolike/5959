package com.ogu.safety.domain

import com.ogu.post.ContentType
import com.ogu.post.ModerationTarget
import com.ogu.safety.RiskLevel
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/**
 * `risk_assessment` 저장소(005 data-model.md). 대상이 고쳐지면 새 행이 생기고 가장 최근 행이 지금 상태다. 걸린 표현과
 * 본문은 저장하지 않는다(research R13).
 */
@Repository
class RiskAssessmentRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 이 대상이 지금까지 받은 가장 높은 단계. 판정이 없으면 [RiskLevel.NONE]. 알림은 이보다 높아질 때만 만든다. */
    fun highestLevel(
        type: ContentType,
        id: Long,
    ): RiskLevel =
        jdbcClient
            .sql("select level from risk_assessment where target_type = :type and target_id = :id")
            .param("type", type.name)
            .param("id", id)
            .query(String::class.java)
            .list()
            .filterNotNull()
            .maxOfOrNull { RiskLevel.of(it) } ?: RiskLevel.NONE

    /** 고쳐서 새 판정이 생기기 전에, 아직 AI를 기다리던 이전 판정을 닫는다. */
    fun supersedePending(
        type: ContentType,
        id: Long,
        now: Instant,
    ) {
        jdbcClient
            .sql(
                """
                update risk_assessment set status = 'SUPERSEDED', completed_at = :now
                where target_type = :type and target_id = :id and status = 'PENDING'
                """.trimIndent(),
            ).param("now", Timestamp.from(now))
            .param("type", type.name)
            .param("id", id)
            .update()
    }

    /** 키워드 판정을 적고 AI 분류를 기다리는 행을 만든다. 첫 시도는 바로 한다. */
    fun insertPending(
        target: ModerationTarget,
        keywordLevel: RiskLevel,
        now: Instant,
    ): Long =
        jdbcClient
            .sql(
                """
                insert into risk_assessment (target_type, target_id, post_id, author_id, content_version, keyword_level,
                                             level, status, next_attempt_at, created_at)
                values (:type, :id, :postId, :authorId, :contentVersion, :level, :level, 'PENDING', :now, :now)
                returning id
                """.trimIndent(),
            ).param("type", target.type.name)
            .param("id", target.id)
            .param("postId", target.postId)
            .param("authorId", target.authorId)
            .param("contentVersion", Timestamp.from(target.updatedAt))
            .param("level", keywordLevel.name)
            .param("now", Timestamp.from(now))
            .query(Long::class.java)
            .single()

    /** 이 대상에서 AI 분류를 기다리는 가장 최근 판정의 ID. 없으면 null. */
    fun latestPendingId(
        type: ContentType,
        id: Long,
    ): Long? =
        jdbcClient
            .sql(
                """
                select id from risk_assessment
                where target_type = :type and target_id = :id and status = 'PENDING'
                order by id desc limit 1
                """.trimIndent(),
            ).param("type", type.name)
            .param("id", id)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    /**
     * 시도할 차례인 판정 하나를 맡는다. 맡으면서 시도 횟수를 올리고 다음 시각을 [nextAttemptAt]으로 미뤄 두므로, 결과를
     * 적기 전에 다른 실행기가 같은 행을 다시 맡지 않는다. [assessmentId]를 주면 그 행만 본다. 다른 실행기가 잡고 있거나
     * 차례가 아니면 null이다.
     */
    fun claimDue(
        now: Instant,
        nextAttemptAt: (attempts: Int) -> Instant,
        assessmentId: Long? = null,
    ): RiskClaim? {
        val due =
            jdbcClient
                .sql(
                    """
                    select id, target_type, target_id, post_id, author_id, content_version, keyword_level, attempts,
                           created_at
                    from risk_assessment
                    where status = 'PENDING' and next_attempt_at <= :now
                      ${if (assessmentId != null) "and id = :id" else ""}
                    order by next_attempt_at
                    limit 1
                    for update skip locked
                    """.trimIndent(),
                ).param("now", Timestamp.from(now))
                .apply { if (assessmentId != null) param("id", assessmentId) }
                .query { rs, _ ->
                    RiskClaim(
                        assessmentId = rs.getLong("id"),
                        type = ContentType.valueOf(rs.getString("target_type")),
                        targetId = rs.getLong("target_id"),
                        postId = rs.getLong("post_id"),
                        authorId = rs.getLong("author_id"),
                        contentVersion = rs.getTimestamp("content_version").toInstant(),
                        keywordLevel = RiskLevel.of(rs.getString("keyword_level")),
                        attempts = rs.getInt("attempts") + 1,
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                    )
                }.optional()
                .orElse(null) ?: return null
        jdbcClient
            .sql("update risk_assessment set attempts = :attempts, next_attempt_at = :next where id = :id")
            .param("attempts", due.attempts)
            .param("next", Timestamp.from(nextAttemptAt(due.attempts)))
            .param("id", due.assessmentId)
            .update()
        return due
    }

    /**
     * 맡은 시도의 결과로 판정을 닫는다. 맡은 뒤 다른 실행기가 먼저 닫았거나 시도 횟수가 달라졌으면 0행이라 false다.
     * 결과는 한 번만 남는다.
     */
    fun close(
        claim: RiskClaim,
        status: String,
        aiLevel: RiskLevel?,
        level: RiskLevel,
        now: Instant,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update risk_assessment
                set status = :status, ai_level = :aiLevel, level = :level, completed_at = :now, last_error = null
                where id = :id and status = 'PENDING' and attempts = :attempts
                """.trimIndent(),
            ).param("status", status)
            .param("aiLevel", aiLevel?.name)
            .param("level", level.name)
            .param("now", Timestamp.from(now))
            .param("id", claim.assessmentId)
            .param("attempts", claim.attempts)
            .update() == 1

    /** 실패한 시도의 분류만 적는다. 다음 시각은 맡을 때 이미 미뤄 두었다. */
    fun recordFailure(
        claim: RiskClaim,
        errorKind: String,
    ) {
        jdbcClient
            .sql(
                "update risk_assessment set last_error = :error " +
                    "where id = :id and status = 'PENDING' and attempts = :attempts",
            ).param("error", errorKind)
            .param("id", claim.assessmentId)
            .param("attempts", claim.attempts)
            .update()
    }
}

/** 맡은 판정 시도 하나. [attempts]는 이번 시도까지 센 횟수다. */
data class RiskClaim(
    val assessmentId: Long,
    val type: ContentType,
    val targetId: Long,
    val postId: Long,
    val authorId: Long,
    val contentVersion: Instant,
    val keywordLevel: RiskLevel,
    val attempts: Int,
    val createdAt: Instant,
)
