package com.ogu.safety.application

import com.ogu.post.CommentRemoved
import com.ogu.post.ContentType
import com.ogu.post.PostApi
import com.ogu.post.PostRemoved
import com.ogu.safety.domain.NewReport
import com.ogu.safety.domain.ReportReason
import com.ogu.safety.domain.ReportRepository
import com.ogu.safety.domain.ReviewRequestRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.text.Grapheme
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 글과 댓글 신고(005 US3, research R9). 신고는 기록만 하고 대상을 숨기지 않는다. 몇 건이 쌓여도 마찬가지다(FR-017).
 * 누가 신고했는지, 신고당했는지는 어떤 응답에도 싣지 않는다(FR-010).
 */
@Service
class ReportService(
    private val postApi: PostApi,
    private val reports: ReportRepository,
    private val properties: SafetyProperties,
    private val clock: Clock,
) {
    /**
     * 보이는 대상만 신고할 수 있다. 숨겼거나 지운 대상은 404, 자기 것은 403 `CANNOT_REPORT_OWN_CONTENT`, 한 시간 한도를
     * 넘으면 429 `REPORT_RATE_LIMITED`, 이미 신고한 대상이면 409 `ALREADY_REPORTED`다.
     */
    @Transactional
    fun report(
        reporterId: Long,
        type: ContentType,
        targetId: Long,
        reason: ReportReason,
        detail: String?,
    ) {
        val normalizedDetail = normalizeDetail(reason, detail)
        val target = visibleTarget(type, targetId)
        if (target.authorId == reporterId) throw BusinessException(ErrorCode.CANNOT_REPORT_OWN_CONTENT)

        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        checkRateLimit(reporterId, now)
        val report = NewReport(reporterId, type, targetId, target.postId, reason, normalizedDetail, now)
        reports.insertIfAbsent(report) ?: throw BusinessException(ErrorCode.ALREADY_REPORTED)
    }

    /** 설명은 기타일 때만 받는다. 다른 사유에 딸려 온 설명은 버린다. 200글자를 넘으면 400이다. */
    private fun normalizeDetail(
        reason: ReportReason,
        detail: String?,
    ): String? {
        val text = detail?.trim().takeIf { reason == ReportReason.OTHER && !it.isNullOrEmpty() } ?: return null
        if (Grapheme.count(text, limit = DETAIL_MAX_LENGTH + 1) > DETAIL_MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "신고 설명은 ${DETAIL_MAX_LENGTH}자까지 쓸 수 있습니다.")
        }
        return text
    }

    private fun visibleTarget(
        type: ContentType,
        targetId: Long,
    ): ReportTarget =
        when (type) {
            ContentType.POST ->
                postApi.findVisible(targetId)?.let { ReportTarget(it.postId, it.authorId) }
                    ?: throw BusinessException(ErrorCode.POST_NOT_FOUND)

            ContentType.COMMENT ->
                postApi.findComment(targetId)?.let { ReportTarget(it.postId, it.authorId) }
                    ?: throw BusinessException(ErrorCode.COMMENT_NOT_FOUND)
        }

    /** 회원 단위 잠금을 잡고 최근 한 시간의 신고 수를 센다. 같은 회원의 동시 요청도 한도 안에서만 통과한다. */
    private fun checkRateLimit(
        reporterId: Long,
        now: Instant,
    ) {
        reports.lockReporter(reporterId)
        val since = now.minus(WINDOW)
        if (reports.countSince(reporterId, since) < properties.report.maxPerHour) return

        val oldest = reports.oldestSince(reporterId, since)
        val retryAfter = oldest?.let { Duration.between(now, it.plus(WINDOW)) } ?: WINDOW
        throw BusinessException(
            ErrorCode.REPORT_RATE_LIMITED,
            retryAfterSeconds = retryAfter.toSeconds().coerceAtLeast(1).toInt(),
        )
    }

    private data class ReportTarget(
        val postId: Long,
        val authorId: Long,
    )

    private companion object {
        const val DETAIL_MAX_LENGTH = 200
        val WINDOW: Duration = Duration.ofHours(1)
    }
}

/**
 * 대상이 지워지면 그 대상의 열린 신고와 재검토 요청을 닫는다(005 research R9). 삭제와 같은 트랜잭션에서 돈다.
 * 글이 지워지면 그 글에 달린 댓글의 신고도 함께 닫는다.
 */
@Service
class RemovalListener(
    private val reports: ReportRepository,
    private val reviews: ReviewRequestRepository,
    private val clock: Clock,
) {
    @EventListener
    fun on(event: PostRemoved) {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        reports.closeOpenByPost(event.postId, CLOSED, now)
        reviews.closeOpenByPost(event.postId, KEPT, now)
    }

    @EventListener
    fun on(event: CommentRemoved) {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        reports.closeOpenByTarget(ContentType.COMMENT, event.commentId, CLOSED, now)
        reviews.closeOpenByTarget(ContentType.COMMENT, event.commentId, KEPT, now)
    }

    private companion object {
        const val CLOSED = "CLOSED"
        const val KEPT = "KEPT"
    }
}
