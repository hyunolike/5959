package com.ogu.safety.application

import com.ogu.ai.ClassifiedRisk
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.RiskClassificationFailed
import com.ogu.ai.RiskClassifier
import com.ogu.post.CommentWritten
import com.ogu.post.ContentType
import com.ogu.post.PostWritten
import com.ogu.safety.RiskLevel
import com.ogu.safety.domain.RiskAssessmentRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation

/**
 * AI 위험 분류 시도 한 번(005 research R3): 맡기(짧은 트랜잭션) → LLM 호출(트랜잭션 밖) → 결과 적기(짧은 트랜잭션).
 * 분류가 실패해도 글 저장과 키워드 판정에는 영향이 없다. 다시 시도하는 일은 [RiskRetryScheduler]가 맡는다.
 */
@Component
class RiskClassificationRunner(
    private val store: RiskAssessmentStore,
    private val assessments: RiskAssessmentRepository,
    private val classifier: RiskClassifier,
    private val properties: SafetyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 글이 커밋되면 바로 한 번 시도한다. 리스너는 트랜잭션 없이 돈다(LLM을 부르는 동안 커넥션을 쥐지 않는다). */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: PostWritten) = attempt(ContentType.POST, event.postId)

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: CommentWritten) = attempt(ContentType.COMMENT, event.commentId)

    fun attempt(
        type: ContentType,
        targetId: Long,
    ) {
        val assessmentId = assessments.latestPendingId(type, targetId) ?: return
        store.claim(assessmentId)?.let(::execute)
    }

    /** 시도할 차례인 판정을 최대 `batch-size`개까지 하나씩 맡아 시도한다. 맡은 개수를 돌려준다. */
    fun runDue(): Int {
        var handled = 0
        while (handled < properties.retry.batchSize) {
            val result = store.claimNextDue() ?: break
            if (result is RiskClaimResult.Claimed) execute(result.attempt)
            handled++
        }
        return handled
    }

    @Suppress("TooGenericExceptionCaught") // 분류기의 어떤 실패도 재시도 일정으로 넘긴다(constitution V)
    private fun execute(attempt: RiskAttempt) {
        val claim = attempt.claim
        val classified =
            try {
                classifier.classify("${claim.type}:${claim.targetId}", attempt.content)
            } catch (e: RiskClassificationFailed) {
                store.recordFailure(claim, e.kind.name)
                return
            } catch (e: RuntimeException) {
                val cause = e.javaClass.simpleName
                log.warn("위험 분류기가 예상하지 못한 예외를 던졌습니다: assessmentId={}, cause={}", claim.assessmentId, cause)
                store.recordFailure(claim, EmotionAnalysisFailed.Kind.UPSTREAM_ERROR.name)
                return
            }
        store.recordSuccess(claim, classified.toRiskLevel())
    }

    private fun ClassifiedRisk.toRiskLevel(): RiskLevel =
        when (this) {
            ClassifiedRisk.NONE -> RiskLevel.NONE
            ClassifiedRisk.CONCERN -> RiskLevel.CONCERN
            ClassifiedRisk.CRISIS -> RiskLevel.CRISIS
        }
}

/**
 * `poll-interval`(10초)마다 시도할 차례인 판정을 다시 시도한다. 여러 인스턴스가 돌아도 `FOR UPDATE SKIP LOCKED`와 맡을 때
 * 미뤄 두는 다음 시각 덕분에 같은 행을 두 번 맡지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.safety.retry", name = ["scheduler-enabled"], matchIfMissing = true)
class RiskRetryScheduler(
    private val runner: RiskClassificationRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("TooGenericExceptionCaught") // 한 번 실패해도 다음 주기에 다시 돈다
    @Scheduled(fixedDelayString = "\${ogu.safety.retry.poll-interval}")
    fun poll() {
        try {
            val handled = runner.runDue()
            if (handled > 0) log.info("위험 분류 재시도: {}건", handled)
        } catch (e: RuntimeException) {
            log.error("위험 분류 재시도 주기가 실패했습니다", e)
        }
    }
}
