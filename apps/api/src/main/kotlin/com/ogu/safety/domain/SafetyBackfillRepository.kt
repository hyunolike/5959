package com.ogu.safety.domain

import com.ogu.post.ContentType
import com.ogu.post.ModerationTarget
import com.ogu.safety.RiskLevel
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** 이미 있는 글과 댓글을 한 번 훑는 작업의 진행 표지와 기록(005 research R14). */
@Repository
class SafetyBackfillRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 트랜잭션이 끝날 때까지 훑기를 혼자 맡는다. 다른 인스턴스가 맡고 있으면 false다. */
    fun tryLock(): Boolean =
        jdbcClient
            .sql("select pg_try_advisory_xact_lock(:namespace, 0)")
            .param("namespace", LOCK_NAMESPACE)
            .query(Boolean::class.java)
            .single()

    /** 아직 끝나지 않은 종류의 마지막으로 훑은 ID. 끝났으면 null이다. */
    fun progress(type: ContentType): Long? =
        jdbcClient
            .sql("select last_id from safety_backfill where target_type = :type and finished_at is null")
            .param("type", type.name)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    fun advance(
        type: ContentType,
        lastId: Long,
        finishedAt: Instant?,
    ) {
        jdbcClient
            .sql("update safety_backfill set last_id = :lastId, finished_at = :finishedAt where target_type = :type")
            .param("lastId", lastId)
            .param("finishedAt", finishedAt?.let(Timestamp::from))
            .param("type", type.name)
            .update()
    }

    /** [ids] 가운데 이미 판정 기록이 있는 것. 출시 뒤에 쓰인 글은 저장할 때 판정됐으므로 다시 보지 않는다. */
    fun assessedIds(
        type: ContentType,
        ids: Collection<Long>,
    ): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        return jdbcClient
            .sql("select distinct target_id from risk_assessment where target_type = :type and target_id in (:ids)")
            .param("type", type.name)
            .param("ids", ids)
            .query { rs, _ -> rs.getLong("target_id") }
            .list()
            .toSet()
    }

    /** 키워드 판정만으로 닫힌 기록을 남긴다. AI 분류는 예약하지 않는다. */
    fun insertKeywordOnly(
        target: ModerationTarget,
        level: RiskLevel,
        now: Instant,
    ) {
        jdbcClient
            .sql(
                """
                insert into risk_assessment (target_type, target_id, post_id, author_id, content_version, keyword_level,
                                             level, status, next_attempt_at, created_at, completed_at)
                values (:type, :id, :postId, :authorId, :contentVersion, :level, :level, 'FALLBACK', :now, :now, :now)
                """.trimIndent(),
            ).param("type", target.type.name)
            .param("id", target.id)
            .param("postId", target.postId)
            .param("authorId", target.authorId)
            .param("contentVersion", Timestamp.from(target.updatedAt))
            .param("level", level.name)
            .param("now", Timestamp.from(now))
            .update()
    }

    private companion object {
        /** advisory lock 두 정수 키 중 첫째. 훑기 전용 값이다. */
        const val LOCK_NAMESPACE = 0x62636b66 // "bckf"
    }
}
