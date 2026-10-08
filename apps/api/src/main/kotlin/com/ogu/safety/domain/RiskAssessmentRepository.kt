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
}
