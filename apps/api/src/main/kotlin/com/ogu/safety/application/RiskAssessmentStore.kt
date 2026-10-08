package com.ogu.safety.application

import com.ogu.post.ModerationTarget
import com.ogu.post.PostModerationApi
import com.ogu.safety.RiskDetected
import com.ogu.safety.RiskLevel
import com.ogu.safety.domain.RiskAssessmentRepository
import com.ogu.safety.domain.RiskClaim
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 맡은 판정과 분류기에 보낼 원문. */
data class RiskAttempt(
    val claim: RiskClaim,
    val content: String,
)

/**
 * AI 분류 시도의 짧은 트랜잭션들(005 research R3). 맡기와 결과 적기를 나눠, LLM을 부르는 동안 DB 커넥션을 쥐지 않는다.
 * 감정 분석의 `AnalysisStore`와 같은 구조다.
 */
@Component
class RiskAssessmentStore(
    private val assessments: RiskAssessmentRepository,
    private val moderation: PostModerationApi,
    private val properties: SafetyProperties,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 대상에서 기다리는 판정이 지금 시도할 차례면 맡는다. */
    @Transactional
    fun claim(assessmentId: Long): RiskAttempt? {
        val now = now()
        val claim = assessments.claimDue(now, { nextAttemptAt(now, it) }, assessmentId) ?: return null
        return prepare(claim, now)
    }

    /** 시도할 차례인 판정 하나를 맡는다. 맡을 것이 없으면 null, 분류 없이 닫았으면 [RiskClaimResult.Closed]다. */
    @Transactional
    fun claimNextDue(): RiskClaimResult? {
        val now = now()
        val claim = assessments.claimDue(now, { nextAttemptAt(now, it) }) ?: return null
        return prepare(claim, now)?.let { RiskClaimResult.Claimed(it) } ?: RiskClaimResult.Closed
    }

    /**
     * 분류 결과를 적는다. 키워드 단계와 견줘 더 높은 쪽이 최종이다(US2-AC2). 최종 단계가 지금까지보다 높아졌으면 그때
     * 단계를 적고, 위기면 숨기고, 작성자에게 알린다. 분류하는 사이에 대상이 고쳐졌으면 결과를 버린다(research R12).
     */
    @Transactional
    fun recordSuccess(
        claim: RiskClaim,
        aiLevel: RiskLevel,
    ) {
        val now = now()
        if (currentTarget(claim) == null) {
            assessments.close(claim, SUPERSEDED, aiLevel = null, level = claim.keywordLevel, now)
            return
        }
        val level = claim.keywordLevel.max(aiLevel)
        val highestBefore = assessments.highestLevel(claim.type, claim.targetId)
        if (assessments.close(claim, DONE, aiLevel, level, now)) {
            if (level > claim.keywordLevel) raise(claim, level, highestBefore)
        } else {
            log.info("다른 실행기가 먼저 적은 위험 분류라 결과를 버립니다: assessmentId={}", claim.assessmentId)
        }
    }

    /** AI가 키워드 규칙보다 높게 봤다. 저장 때 하지 못한 대응을 지금 한다. */
    private fun raise(
        claim: RiskClaim,
        level: RiskLevel,
        highestBefore: RiskLevel,
    ) {
        moderation.markRisk(claim.type, claim.targetId, level.name)
        if (level == RiskLevel.CRISIS) moderation.hide(claim.type, claim.targetId, ScreeningListener.HIDDEN_BY_RISK)
        if (level > highestBefore) {
            events.publishEvent(RiskDetected(claim.type, claim.targetId, claim.postId, claim.authorId, level))
        }
    }

    @Transactional
    fun recordFailure(
        claim: RiskClaim,
        errorKind: String,
    ) {
        assessments.recordFailure(claim, errorKind)
        log.info(
            "위험 분류 실패, 재시도 예약: assessmentId={}, kind={}, attempts={}",
            claim.assessmentId,
            errorKind,
            claim.attempts,
        )
    }

    /** 기한이 지났거나 대상이 없어졌거나 고쳐졌으면 분류 없이 닫고 null을 돌려준다. */
    private fun prepare(
        claim: RiskClaim,
        now: Instant,
    ): RiskAttempt? {
        val pastDeadline = !now.isBefore(claim.createdAt.plus(properties.retry.deadline))
        val target = if (pastDeadline) null else currentTarget(claim)
        if (target != null) return RiskAttempt(claim, target.content)

        if (pastDeadline) {
            // AI를 끝내 쓰지 못했다. 키워드 규칙의 판정이 최종이 된다(FR-003)
            log.warn("위험 분류가 기한 안에 끝나지 않아 키워드 판정으로 닫습니다: assessmentId={}", claim.assessmentId)
        }
        val status = if (pastDeadline) FALLBACK else SUPERSEDED
        assessments.close(claim, status, aiLevel = null, level = claim.keywordLevel, now)
        return null
    }

    /** 맡은 판정이 본 판(版)과 같은 내용의 대상. 지웠거나 그사이 고쳐졌으면 null이다. */
    private fun currentTarget(claim: RiskClaim): ModerationTarget? =
        moderation
            .contentOf(claim.type, claim.targetId)
            ?.takeIf { it.updatedAt.truncatedTo(ChronoUnit.MICROS) == claim.contentVersion }

    /** n번째 시도가 실패했을 때의 다음 시각: `min(initial × 2^(n-1), max-interval)` 뒤. */
    private fun nextAttemptAt(
        now: Instant,
        attempts: Int,
    ): Instant {
        val retry = properties.retry
        val factor = 1L shl (attempts - 1).coerceIn(0, MAX_SHIFT)
        val delay = retry.initial.multipliedBy(factor).coerceAtMost(retry.maxInterval)
        return now.plus(delay)
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun Duration.coerceAtMost(max: Duration): Duration = if (this > max) max else this

    private companion object {
        const val DONE = "DONE"
        const val FALLBACK = "FALLBACK"
        const val SUPERSEDED = "SUPERSEDED"
        const val MAX_SHIFT = 20
    }
}

sealed interface RiskClaimResult {
    data class Claimed(
        val attempt: RiskAttempt,
    ) : RiskClaimResult

    /** 분류 없이 닫았다(기한이 지났거나 대상이 없어졌다). */
    data object Closed : RiskClaimResult
}
