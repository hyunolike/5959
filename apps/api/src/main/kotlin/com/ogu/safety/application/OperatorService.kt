package com.ogu.safety.application

import com.ogu.post.ContentType
import com.ogu.post.ModerationTarget
import com.ogu.post.PostModerationApi
import com.ogu.safety.ContentRestored
import com.ogu.safety.ReviewResolved
import com.ogu.safety.domain.ModerationActionRepository
import com.ogu.safety.domain.ModerationActionType
import com.ogu.safety.domain.NewModerationAction
import com.ogu.safety.domain.ReportRepository
import com.ogu.safety.domain.ReviewRequestRepository
import com.ogu.safety.domain.RiskAssessmentRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.text.Grapheme
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 운영자의 처리(005 US4, research R10, R11). 상태를 실제로 바꾼 처리만 `moderation_action`에 남긴다(FR-012). 숨김과 해제는
 * 대상 행의 UPDATE 한 문장이 가르므로, 겹쳐 불려도 상태를 바꾼 쪽만 기록하고 기록의 순서가 상태와 어긋나지 않는다.
 * 없는 대상이나 기록은 404 `NOT_FOUND`다.
 */
@Service
class OperatorService(
    private val moderation: PostModerationApi,
    private val reports: ReportRepository,
    private val reviews: ReviewRequestRepository,
    private val actions: ModerationActionRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** 숨기고 그 대상의 열린 신고를 모두 처리됨으로 닫는다(US4-AC4). 이미 숨겨져 있으면 아무것도 하지 않는다. */
    @Transactional
    fun hide(
        operatorId: Long,
        type: ContentType,
        targetId: Long,
        note: String?,
    ) {
        val cleanNote = OperatorNote.clean(note)
        moderation.contentOf(type, targetId) ?: throw BusinessException(ErrorCode.NOT_FOUND)
        if (!moderation.hide(type, targetId, HIDDEN_BY_OPERATOR)) return

        reports.closeOpenByTarget(type, targetId, RESOLVED, now())
        record(operatorId, ModerationActionType.HIDE, type, targetId, cleanNote)
    }

    /** 숨김을 풀고 작성자에게 알린다(US4-AC3). 숨겨져 있지 않으면 아무것도 하지 않는다. 지운 대상은 404다. */
    @Transactional
    fun unhide(
        operatorId: Long,
        type: ContentType,
        targetId: Long,
    ) {
        val target = moderation.contentOf(type, targetId) ?: throw BusinessException(ErrorCode.NOT_FOUND)
        restore(operatorId, target, note = null)
    }

    /** 신고 하나를 처리됨이나 기각으로 닫는다(US4-AC5). 대상은 건드리지 않는다. 이미 닫힌 신고면 아무것도 하지 않는다. */
    @Transactional
    fun decideReport(
        operatorId: Long,
        reportId: Long,
        resolve: Boolean,
        note: String?,
    ) {
        val cleanNote = OperatorNote.clean(note)
        val (type, targetId) = reports.targetOf(reportId) ?: throw BusinessException(ErrorCode.NOT_FOUND)
        if (!reports.close(reportId, if (resolve) RESOLVED else REJECTED, now())) return

        val action = if (resolve) ModerationActionType.RESOLVE_REPORT else ModerationActionType.REJECT_REPORT
        record(operatorId, action, type, targetId, cleanNote)
    }

    /**
     * 재검토 요청을 닫는다(US4-AC3, AC9). [restore]면 숨김을 풀고, 아니면 숨긴 채 두고 작성자에게 결과를 알린다.
     * 이미 닫힌 요청이면 아무것도 하지 않는다.
     */
    @Transactional
    fun decideReview(
        operatorId: Long,
        reviewId: Long,
        restore: Boolean,
        note: String?,
    ) {
        val cleanNote = OperatorNote.clean(note)
        val review = reviews.find(reviewId) ?: throw BusinessException(ErrorCode.NOT_FOUND)
        if (!reviews.close(reviewId, if (restore) RESTORED else KEPT, now())) return

        if (restore) {
            moderation.contentOf(review.type, review.targetId)?.let { restore(operatorId, it, cleanNote) }
            return
        }
        record(operatorId, ModerationActionType.KEEP_HIDDEN, review.type, review.targetId, cleanNote)
        events.publishEvent(
            ReviewResolved(review.id, review.type, review.targetId, review.postId, review.requesterId, kept = true),
        )
    }

    private fun restore(
        operatorId: Long,
        target: ModerationTarget,
        note: String?,
    ) {
        if (!moderation.unhide(target.type, target.id)) return

        reviews.closeOpenByTarget(target.type, target.id, RESTORED, now())
        val actionId = record(operatorId, ModerationActionType.UNHIDE, target.type, target.id, note)
        events.publishEvent(ContentRestored(target.type, target.id, target.postId, target.authorId, actionId))
    }

    private fun record(
        operatorId: Long,
        action: ModerationActionType,
        type: ContentType,
        targetId: Long,
        note: String?,
    ): Long = actions.record(NewModerationAction(operatorId, action, type.name, targetId, note, now()))

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        const val HIDDEN_BY_OPERATOR = "OPERATOR"
        const val RESOLVED = "RESOLVED"
        const val REJECTED = "REJECTED"
        const val RESTORED = "RESTORED"
        const val KEPT = "KEPT"
    }
}

/** 운영자가 판정을 확인했다고 표시한다(005 US4-AC1). 처리 기록은 판정 행의 확인 시각이 맡는다. */
@Service
class AssessmentReviewService(
    private val assessments: RiskAssessmentRepository,
    private val clock: Clock,
) {
    /** 이미 확인했으면 처음 시각을 그대로 둔다. 없는 판정이면 404 `NOT_FOUND`다. */
    @Transactional
    fun markReviewed(assessmentId: Long) {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (!assessments.markReviewed(assessmentId, now)) throw BusinessException(ErrorCode.NOT_FOUND)
    }
}

/** 처리 기록에 남기는 메모. 비었으면 null이고 200글자를 넘으면 400이다. */
internal object OperatorNote {
    private const val MAX_LENGTH = 200

    fun clean(raw: String?): String? {
        val note = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        if (Grapheme.count(note, limit = MAX_LENGTH + 1) > MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "메모는 ${MAX_LENGTH}자까지 쓸 수 있습니다.")
        }
        return note
    }
}
