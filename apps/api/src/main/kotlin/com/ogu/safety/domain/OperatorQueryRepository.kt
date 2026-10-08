package com.ogu.safety.domain

import com.ogu.post.ContentType
import com.ogu.safety.RiskLevel
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 운영자 조회(005 research R10). 셋 모두 ID 내림차순 키셋이다. ID는 만든 순서대로 커지므로 최신순과 같고, 쪽 사이에 새 행이
 * 생겨도 앞 쪽에 나온 행이 다시 나오거나 빠지지 않는다.
 */
@Repository
class OperatorQueryRepository(
    private val jdbcClient: JdbcClient,
) {
    /** [level]을 주지 않으면 단계가 NONE이 아닌 판정만 준다. */
    fun assessments(
        level: RiskLevel?,
        reviewed: Boolean?,
        page: KeysetPage,
    ): List<AssessmentRow> {
        val conditions =
            listOfNotNull(
                if (level == null) "level <> 'NONE'" else "level = :level",
                reviewed?.let { if (it) "reviewed_at is not null" else "reviewed_at is null" },
                page.beforeId?.let { "id < :beforeId" },
            )
        var spec =
            jdbcClient
                .sql(
                    """
                    select id, target_type, target_id, post_id, author_id, keyword_level, ai_level, level, status,
                           reviewed_at is not null as reviewed, created_at
                    from risk_assessment
                    where ${conditions.joinToString(" and ")}
                    order by id desc
                    limit :limit
                    """.trimIndent(),
                ).param("limit", page.limit)
        if (level != null) spec = spec.param("level", level.name)
        if (page.beforeId != null) spec = spec.param("beforeId", page.beforeId)
        return spec
            .query { rs, _ ->
                AssessmentRow(
                    id = rs.getLong("id"),
                    type = ContentType.valueOf(rs.getString("target_type")),
                    targetId = rs.getLong("target_id"),
                    postId = rs.getLong("post_id"),
                    authorId = rs.getLong("author_id"),
                    keywordLevel = RiskLevel.of(rs.getString("keyword_level")),
                    aiLevel = rs.getString("ai_level")?.let(RiskLevel::of),
                    level = RiskLevel.of(rs.getString("level")),
                    status = rs.getString("status"),
                    reviewed = rs.getBoolean("reviewed"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }.list()
    }

    /** [ReportRow.openReportCount]는 같은 대상의 열린 신고 수다. 이 행이 닫혀 있어도 대상에 열린 신고가 남아 있으면 센다. */
    fun reports(
        status: String,
        page: KeysetPage,
    ): List<ReportRow> {
        val before = if (page.beforeId == null) "" else "and r.id < :beforeId"
        var spec =
            jdbcClient
                .sql(
                    """
                    select r.id, r.reporter_id, r.target_type, r.target_id, r.post_id, r.reason, r.detail, r.status,
                           r.created_at,
                           (select count(*) from report o
                            where o.target_type = r.target_type and o.target_id = r.target_id
                              and o.status = 'PENDING') as open_count
                    from report r
                    where r.status = :status $before
                    order by r.id desc
                    limit :limit
                    """.trimIndent(),
                ).param("status", status)
                .param("limit", page.limit)
        if (page.beforeId != null) spec = spec.param("beforeId", page.beforeId)
        return spec
            .query { rs, _ ->
                ReportRow(
                    id = rs.getLong("id"),
                    reporterId = rs.getLong("reporter_id"),
                    type = ContentType.valueOf(rs.getString("target_type")),
                    targetId = rs.getLong("target_id"),
                    postId = rs.getLong("post_id"),
                    reason = ReportReason.valueOf(rs.getString("reason")),
                    detail = rs.getString("detail"),
                    openReportCount = rs.getInt("open_count"),
                    status = rs.getString("status"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }.list()
    }

    fun reviews(
        status: String,
        page: KeysetPage,
    ): List<ReviewRow> {
        val before = if (page.beforeId == null) "" else "and id < :beforeId"
        var spec =
            jdbcClient
                .sql(
                    """
                    select id, target_type, target_id, post_id, requester_id, status, created_at
                    from review_request
                    where status = :status $before
                    order by id desc
                    limit :limit
                    """.trimIndent(),
                ).param("status", status)
                .param("limit", page.limit)
        if (page.beforeId != null) spec = spec.param("beforeId", page.beforeId)
        return spec
            .query { rs, _ ->
                ReviewRow(
                    id = rs.getLong("id"),
                    type = ContentType.valueOf(rs.getString("target_type")),
                    targetId = rs.getLong("target_id"),
                    postId = rs.getLong("post_id"),
                    requesterId = rs.getLong("requester_id"),
                    status = rs.getString("status"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }.list()
    }
}

/** [beforeId]보다 작은 ID부터 [limit]개. 첫 쪽이면 [beforeId]는 null이다. */
data class KeysetPage(
    val beforeId: Long?,
    val limit: Int,
)

data class AssessmentRow(
    val id: Long,
    val type: ContentType,
    val targetId: Long,
    val postId: Long,
    val authorId: Long,
    val keywordLevel: RiskLevel,
    val aiLevel: RiskLevel?,
    val level: RiskLevel,
    val status: String,
    val reviewed: Boolean,
    val createdAt: Instant,
)

data class ReportRow(
    val id: Long,
    val reporterId: Long,
    val type: ContentType,
    val targetId: Long,
    val postId: Long,
    val reason: ReportReason,
    val detail: String?,
    val openReportCount: Int,
    val status: String,
    val createdAt: Instant,
)

data class ReviewRow(
    val id: Long,
    val type: ContentType,
    val targetId: Long,
    val postId: Long,
    val requesterId: Long,
    val status: String,
    val createdAt: Instant,
)
