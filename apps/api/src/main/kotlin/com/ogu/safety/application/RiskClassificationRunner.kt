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
import com.ogu.shared.schedule.DueJob
import com.ogu.shared.schedule.DueTimers
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import java.time.Duration
import java.time.Instant

/**
 * AI 위험 분류 시도 한 번(005 research R3): 맡기(짧은 트랜잭션) → LLM 호출(트랜잭션 밖) → 결과 적기(짧은 트랜잭션).
 * 분류가 실패해도 글 저장과 키워드 판정에는 영향이 없다. 다시 시도하는 일은 [RiskRetryScheduler]가 맡는다.
 *
 * 기다리는 행은 저장 트랜잭션 안에서 [ScreeningListener]가 만들고, 커밋 뒤 첫 시도는 여기서 한다. 첫 시도의 결과(성공,
 * 또는 실패의 다음 시각)는 이미 커밋됐으므로, 끝나면 타이머를 다시 건다(018 research R3). 스케줄러를 끈 테스트에서는
 * 타이머가 없다.
 */
@Component
class RiskClassificationRunner(
    private val store: RiskAssessmentStore,
    private val assessments: RiskAssessmentRepository,
    private val classifier: RiskClassifier,
    private val properties: SafetyProperties,
    private val retry: ObjectProvider<RiskRetryScheduler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 글이 커밋되면 바로 한 번 시도한다. 리스너는 트랜잭션 없이 돈다(LLM을 부르는 동안 커넥션을 쥐지 않는다). */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: PostWritten) = attemptThenRearm(ContentType.POST, event.postId)

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: CommentWritten) = attemptThenRearm(ContentType.COMMENT, event.commentId)

    private fun attemptThenRearm(
        type: ContentType,
        targetId: Long,
    ) {
        try {
            attempt(type, targetId)
        } finally {
            retry.ifAvailable { it.rearm() }
        }
    }

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
 * 시도할 차례인 판정을 다시 시도한다. 018 research R3부터는 주기마다 DB를 읽지 않고, 가장 이른 `next_attempt_at`에만
 * 깨어난다. 할 일이 없으면 DB에 묻지 않으므로 Neon이 잠들 수 있다.
 *
 * - 돌고 나면 [nextDue]로 다음 시각을 다시 읽는다. 맡기(임대)와 실패(백오프)로 바뀐 시각은 이것으로 잡힌다.
 * - 글과 댓글이 저장된 직후의 첫 시도는 [RiskClassificationRunner]가 하고, 끝나면 [rearm]을 부른다. 실패했다면 그
 *   백오프 시각이 걸린다.
 * - 여러 인스턴스가 돌아도 `FOR UPDATE SKIP LOCKED`와 맡을 때 미뤄 두는 다음 시각 덕분에 같은 행을 두 번 맡지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.safety.retry", name = ["scheduler-enabled"], matchIfMissing = true)
class RiskRetryScheduler(
    private val runner: RiskClassificationRunner,
    private val assessments: RiskAssessmentRepository,
    // 밀린 재시도는 예전 폴링 간격마다 한 묶음씩 보낸다. AI 제공자의 요청 한도에 몰리지 않게 한다.
    @Value("\${ogu.safety.retry.poll-interval:10s}") override val minGap: Duration,
    timers: DueTimers,
) : DueJob {
    private val log = LoggerFactory.getLogger(javaClass)
    private val timer = timers.register(this)

    override val name = "위험 분류 재시도"

    override fun nextDue(): Instant? = assessments.findEarliestPendingAttemptAt()

    override fun runDue() {
        val handled = runner.runDue()
        if (handled > 0) log.info("위험 분류 재시도: {}건", handled)
    }

    /** 다음 시각을 DB에서 다시 읽어 건다. 행을 만들거나 시각을 바꾼 쪽이 커밋 뒤에 부른다. */
    fun rearm() = timer.rearm()

    /** 지금 걸린 다음 시각. 테스트와 로그용이다. */
    val nextAt: Instant?
        get() = timer.nextAt
}
