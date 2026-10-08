package com.ogu.emotion.application

import com.ogu.ai.EmotionClassification
import com.ogu.emotion.AnalysisStatus
import com.ogu.emotion.domain.EmotionAnalysis
import com.ogu.emotion.domain.EmotionAnalysisRepository
import com.ogu.post.PostApi
import com.ogu.post.PostCreated
import com.ogu.shared.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 실행기가 맡은 시도 하나. [attempts]는 맡을 때의 시도 횟수로, 결과를 기록할 때 그사이 다른 기록이 없었는지 본다. */
data class AnalysisClaim(
    val postId: Long,
    val attempts: Int,
    val content: String,
)

/**
 * 분석 행을 다루는 짧은 트랜잭션들(research R2). LLM 호출은 이 트랜잭션들 밖에서 한다.
 * 맡기([claim], [claimNextDue]) → 커밋 → 호출 → 기록([recordSuccess], [recordFailure]) 순서이고, 행이 ANALYZED나
 * DEFAULTED가 되는 트랜잭션에서 `EmotionAnalyzed`를 발행한다.
 */
@Component
class AnalysisStore(
    private val repository: EmotionAnalysisRepository,
    private val postApi: PostApi,
    private val events: ApplicationEventPublisher,
    private val properties: EmotionRetryProperties,
    private val clock: Clock,
    aiProperties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 맡은 호출이 끝날 때까지 다른 실행기가 손대지 않을 시간: LLM 타임아웃 + 여유 10초. */
    private val leaseGrace: Duration = aiProperties.timeout.plus(LEASE_MARGIN)

    @Transactional
    fun createPending(event: PostCreated) {
        repository.insertPendingIfAbsent(event.postId, event.createdAt.truncatedTo(ChronoUnit.MICROS), now())
    }

    /** 이 글의 분석이 지금 시도할 차례면 맡는다. 다른 실행기가 잡고 있거나, 아직 차례가 아니거나, 끝났으면 null이다. */
    @Transactional
    fun claim(postId: Long): AnalysisClaim? {
        val now = now()
        val analysis = repository.findDueByPostIdForUpdateSkipLocked(postId, now) ?: return null
        return claimLocked(analysis, now)
    }

    /**
     * 시도할 차례인 분석 하나를 맡는다. 맡을 행이 없으면 null, 기한이 지나 기본값으로 끝냈으면 [ClaimResult.Finished]다.
     */
    @Transactional
    fun claimNextDue(): ClaimResult? {
        val now = now()
        val analysis = repository.findDueForUpdateSkipLocked(now, 1).firstOrNull() ?: return null
        return claimLocked(analysis, now)?.let { ClaimResult.Claimed(it) } ?: ClaimResult.Finished
    }

    @Transactional
    fun recordSuccess(
        claim: AnalysisClaim,
        classification: EmotionClassification,
    ) {
        val analysis = lockUnchanged(claim) ?: return
        val event =
            analysis.succeed(
                classification.emotion.toEmotionType(),
                classification.intensity.toIntensity(),
                classification.reason,
                now(),
            )
        events.publishEvent(event)
    }

    @Transactional
    fun recordFailure(
        claim: AnalysisClaim,
        errorKind: String,
    ) {
        val analysis = lockUnchanged(claim) ?: return
        val event = analysis.fail(errorKind, now(), properties.backoff, properties.deadline)
        if (event == null) {
            log.info(
                "감정 분석 실패, 재시도 예약: postId={}, kind={}, attempts={}, nextAttemptAt={}",
                claim.postId,
                errorKind,
                analysis.attempts,
                analysis.nextAttemptAt,
            )
        } else {
            log.warn("감정 분석이 기한 안에 끝나지 않아 기본값을 씁니다: postId={}, kind={}", claim.postId, errorKind)
            events.publishEvent(event)
        }
    }

    /** 기한이 지났으면 기본값으로 끝내고 null을, 아니면 다음 시각을 미뤄 두고 이번 시도를 돌려준다. */
    private fun claimLocked(
        analysis: EmotionAnalysis,
        now: Instant,
    ): AnalysisClaim? {
        val pastDeadline = analysis.isPastDeadline(now, properties.deadline)
        // 분석 전에 지운 글이면 부를 본문이 없으므로 기본값으로 끝내 재시도 대상에서 뺀다.
        val post = if (pastDeadline) null else postApi.find(analysis.postId)
        if (post == null) {
            val reason = if (pastDeadline) "기한이 지남" else "지운 글"
            log.warn("감정 분석 없이 기본값으로 끝냅니다({}): postId={}, attempts={}", reason, analysis.postId, analysis.attempts)
            events.publishEvent(analysis.expire(now))
            return null
        }
        analysis.claim(now, properties.backoff, properties.deadline, leaseGrace)
        return AnalysisClaim(analysis.postId, analysis.attempts, post.content)
    }

    /** 맡은 뒤 다른 실행기가 결과를 먼저 기록했으면(시도 횟수가 달라졌거나 끝났으면) null이다. 결과는 한 번만 남는다. */
    private fun lockUnchanged(claim: AnalysisClaim): EmotionAnalysis? {
        val analysis =
            repository
                .findByPostIdForUpdate(claim.postId)
                ?.takeIf { it.status == AnalysisStatus.PENDING && it.attempts == claim.attempts }
        if (analysis == null) log.info("다른 실행기가 먼저 기록한 시도라 결과를 버립니다: postId={}", claim.postId)
        return analysis
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        val LEASE_MARGIN: Duration = Duration.ofSeconds(10)
    }
}

sealed interface ClaimResult {
    data class Claimed(
        val claim: AnalysisClaim,
    ) : ClaimResult

    /** 기한이 지났거나 글이 지워져 시도 없이 기본값으로 끝냈다. */
    data object Finished : ClaimResult
}
