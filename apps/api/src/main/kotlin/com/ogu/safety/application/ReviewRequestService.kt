package com.ogu.safety.application

import com.ogu.post.ContentType
import com.ogu.post.PostModerationApi
import com.ogu.safety.domain.NewReviewRequest
import com.ogu.safety.domain.ReviewRequestRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 숨겨진 내 글이나 댓글의 재검토 요청(005 US4-AC8, research R11). 대상마다 한 번이고, 닫힌 뒤에도 다시 요청할 수 없다.
 * 접수하면 대상 행에 표지를 적어 글 상세와 댓글 응답이 `safety.reviewRequested`를 자기 열에서 읽게 한다.
 */
@Service
class ReviewRequestService(
    private val moderation: PostModerationApi,
    private val reviews: ReviewRequestRepository,
    private val clock: Clock,
) {
    /** 남의 것이거나, 숨겨지지 않았거나, 지운 대상이면 404다. 이미 요청했으면 409 `REVIEW_ALREADY_REQUESTED`다. */
    @Transactional
    fun request(
        memberId: Long,
        type: ContentType,
        targetId: Long,
    ) {
        val target =
            moderation.contentOf(type, targetId)?.takeIf { it.authorId == memberId && it.hidden }
                ?: throw BusinessException(notFound(type))
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        reviews.insertIfAbsent(NewReviewRequest(type, targetId, target.postId, memberId, now))
            ?: throw BusinessException(ErrorCode.REVIEW_ALREADY_REQUESTED)
        moderation.markReviewRequested(type, targetId)
    }

    private fun notFound(type: ContentType): ErrorCode =
        when (type) {
            ContentType.POST -> ErrorCode.POST_NOT_FOUND
            ContentType.COMMENT -> ErrorCode.COMMENT_NOT_FOUND
        }
}
