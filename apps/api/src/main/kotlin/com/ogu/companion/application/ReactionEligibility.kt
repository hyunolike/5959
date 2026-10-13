package com.ogu.companion.application

import com.ogu.companion.domain.SkipReason
import com.ogu.post.CompanionTarget
import com.ogu.post.PostCompanionApi
import com.ogu.safety.RiskApi
import com.ogu.safety.RiskLevel
import org.springframework.stereotype.Component

sealed interface Eligibility {
    /** 반응할 글이다. */
    data class Eligible(
        val target: CompanionTarget,
    ) : Eligibility

    /** 반응하지 않고 닫는다. */
    data class Closed(
        val reason: SkipReason,
    ) : Eligibility

    /** AI 친구의 댓글이 이미 있다. 남긴 뒤 기록을 적기 전에 멈췄던 경우다. */
    data class AlreadyReacted(
        val commentId: Long,
    ) : Eligibility

    /** 글의 위험 판정이 아직 끝나지 않았다. */
    data object Unsettled : Eligibility
}

/**
 * 글이 지금 AI 친구가 반응할 대상인지 본다(009 research R4의 2~6번). AI를 부르기 전에 끝나는 확인이다. 반응하지 않을 글에는
 * 호출이 나가지 않는다. 글을 고쳤으면 고친 뒤의 상태로 본다.
 */
@Component
class ReactionEligibility(
    private val postApi: PostCompanionApi,
    private val riskApi: RiskApi,
) {
    fun check(
        postId: Long,
        companionMemberIds: Collection<Long>,
    ): Eligibility {
        val target = postApi.target(postId, companionMemberIds)
        val reacted = target?.companionCommentId
        return when {
            // 위기로 판정된 글은 숨겨져 있어 여기서 닫힌다
            target == null || target.hidden -> Eligibility.Closed(SkipReason.GONE)
            reacted != null -> Eligibility.AlreadyReacted(reacted)
            target.otherCommented -> Eligibility.Closed(SkipReason.HUMAN_COMMENT)
            else -> byRisk(target)
        }
    }

    /** 판정이 끝날 때까지 기다리고, 끝난 뒤 위험이 없을 때만 반응한다(FR-007, 원칙 IV). */
    private fun byRisk(target: CompanionTarget): Eligibility {
        val risk = riskApi.postRisk(target.postId)
        return when {
            !risk.settled -> Eligibility.Unsettled
            risk.level != RiskLevel.NONE -> Eligibility.Closed(SkipReason.RISK)
            else -> Eligibility.Eligible(target)
        }
    }
}
