package com.ogu.safety.application

import com.ogu.post.ContentType
import com.ogu.post.ModerationTarget
import com.ogu.post.PostModerationApi
import com.ogu.safety.RiskLevel
import com.ogu.safety.domain.AssessmentRow
import com.ogu.safety.domain.KeysetPage
import com.ogu.safety.domain.OperatorQueryRepository
import com.ogu.safety.domain.ReportReason
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/**
 * 운영자 조회(005 US4-AC1, AC2, AC8, research R10). 판단하려면 봐야 하므로 대상의 원문을 싣는다. 원문은 한 쪽에 종류마다
 * 쿼리 한 번으로 읽는다. 지워진 대상은 본문 없이 `deleted = true`로 준다. 이 조회는 본문을 로그에 남기지 않는다.
 */
@Service
class OperatorQueryService(
    private val queries: OperatorQueryRepository,
    private val moderation: PostModerationApi,
) {
    @Transactional(readOnly = true)
    fun assessments(
        level: RiskLevel?,
        reviewed: Boolean?,
        cursor: String?,
        size: Int,
    ): OperatorPage<OperatorAssessment> {
        val rows = queries.assessments(level, reviewed, keyset(cursor, size))
        val targets = targetsOf(rows.map { it.type to it.targetId })
        return page(rows, size, { it.id }) {
            OperatorAssessment(
                assessmentId = it.id,
                target = target(targets, it.type, it.targetId, it.postId, it.authorId),
                level = it.level,
                method = methodOf(it),
                status = it.status,
                reviewed = it.reviewed,
                createdAt = it.createdAt,
            )
        }
    }

    @Transactional(readOnly = true)
    fun reports(
        status: String,
        cursor: String?,
        size: Int,
    ): OperatorPage<OperatorReport> {
        val rows = queries.reports(status, keyset(cursor, size))
        val targets = targetsOf(rows.map { it.type to it.targetId })
        return page(rows, size, { it.id }) {
            OperatorReport(
                reportId = it.id,
                // 신고 기록에는 작성자가 없다. 지워진 대상이면 작성자를 알 수 없다
                target = target(targets, it.type, it.targetId, it.postId, authorId = null),
                reporterId = it.reporterId,
                reason = it.reason,
                detail = it.detail,
                openReportCount = it.openReportCount,
                status = it.status,
                createdAt = it.createdAt,
            )
        }
    }

    @Transactional(readOnly = true)
    fun reviews(
        status: String,
        cursor: String?,
        size: Int,
    ): OperatorPage<OperatorReview> {
        val rows = queries.reviews(status, keyset(cursor, size))
        val targets = targetsOf(rows.map { it.type to it.targetId })
        return page(rows, size, { it.id }) {
            OperatorReview(
                reviewId = it.id,
                target = target(targets, it.type, it.targetId, it.postId, it.requesterId),
                status = it.status,
                createdAt = it.createdAt,
            )
        }
    }

    private fun targetsOf(keys: List<TargetKey>): Map<TargetKey, ModerationTarget> =
        if (keys.isEmpty()) emptyMap() else moderation.contentsOf(keys.toSet())

    private fun target(
        targets: Map<TargetKey, ModerationTarget>,
        type: ContentType,
        targetId: Long,
        postId: Long,
        authorId: Long?,
    ): OperatorTarget {
        val found = targets[type to targetId]
        return OperatorTarget(
            targetType = type,
            targetId = targetId,
            postId = postId,
            authorId = found?.authorId ?: authorId,
            content = found?.content,
            hidden = found?.hidden ?: false,
            deleted = found == null,
        )
    }

    /** 하나 더 읽어 다음 쪽이 있는지 본다. [size]가 범위 밖이거나 [cursor]가 올바르지 않으면 400 INVALID_REQUEST. */
    private fun keyset(
        cursor: String?,
        size: Int,
    ): KeysetPage {
        if (size !in 1..MAX_PAGE_SIZE) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "size는 1 이상 $MAX_PAGE_SIZE 이하여야 합니다.")
        }
        return KeysetPage(beforeId = cursor?.let(OperatorCursor::decode), limit = size + 1)
    }

    private fun <R, T> page(
        rows: List<R>,
        size: Int,
        idOf: (R) -> Long,
        toItem: (R) -> T,
    ): OperatorPage<T> {
        val shown = rows.take(size)
        val nextCursor = if (rows.size > size) OperatorCursor.encode(idOf(shown.last())) else null
        return OperatorPage(items = shown.map(toItem), nextCursor = nextCursor)
    }

    private fun methodOf(row: AssessmentRow): DetectionMethod {
        val byKeyword = row.keywordLevel != RiskLevel.NONE
        val byAi = row.aiLevel != null && row.aiLevel != RiskLevel.NONE
        return when {
            byKeyword && byAi -> DetectionMethod.BOTH
            byKeyword -> DetectionMethod.KEYWORD
            byAi -> DetectionMethod.AI
            else -> DetectionMethod.NONE
        }
    }

    companion object {
        const val MAX_PAGE_SIZE = 50
    }
}

private typealias TargetKey = Pair<ContentType, Long>

/** 운영자 조회의 키셋 커서. 앞 쪽 마지막 행의 ID를 `base64url("{id}")`로 감싼다. */
internal object OperatorCursor {
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val FORMAT = Regex("^\\d{1,19}$")

    fun encode(lastId: Long): String = ENCODER.encodeToString(lastId.toString().toByteArray(StandardCharsets.UTF_8))

    /** 형식이 틀리면 400 INVALID_REQUEST. */
    fun decode(raw: String): Long =
        runCatching { String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8) }
            .getOrNull()
            ?.takeIf(FORMAT::matches)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "커서가 올바르지 않습니다.")
}

enum class DetectionMethod {
    KEYWORD,
    AI,
    BOTH,
    NONE,
}

/** 계약의 `OperatorTarget`. [content]는 가리지 않은 원문이고 지워졌으면 null이다. */
data class OperatorTarget(
    val targetType: ContentType,
    val targetId: Long,
    val postId: Long,
    val authorId: Long?,
    val content: String?,
    val hidden: Boolean,
    val deleted: Boolean,
)

data class OperatorAssessment(
    val assessmentId: Long,
    val target: OperatorTarget,
    val level: RiskLevel,
    val method: DetectionMethod,
    val status: String,
    val reviewed: Boolean,
    val createdAt: Instant,
)

data class OperatorReport(
    val reportId: Long,
    val target: OperatorTarget,
    val reporterId: Long,
    val reason: ReportReason,
    val detail: String?,
    val openReportCount: Int,
    val status: String,
    val createdAt: Instant,
)

data class OperatorReview(
    val reviewId: Long,
    val target: OperatorTarget,
    val status: String,
    val createdAt: Instant,
)

data class OperatorPage<T>(
    val items: List<T>,
    val nextCursor: String?,
)
