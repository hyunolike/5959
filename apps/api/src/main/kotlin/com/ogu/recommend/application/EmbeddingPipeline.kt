package com.ogu.recommend.application

import com.ogu.ai.Embedder
import com.ogu.ai.EmbeddingFailed
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.post.PostApi
import com.ogu.post.PostRemoved
import com.ogu.post.PostWritten
import com.ogu.recommend.domain.EmbeddingClaim
import com.ogu.recommend.domain.PostEmbeddingRepository
import com.ogu.shared.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 맡은 시도와 공급자에 보낼 본문. */
data class EmbeddingAttempt(
    val claim: EmbeddingClaim,
    val content: String,
)

/**
 * 임베딩 행을 다루는 짧은 트랜잭션들(007 research R4). 공급자 호출은 이 트랜잭션들 밖에서 한다. 맡기 → 커밋 → 호출 →
 * 결과 적기 순서다. M2의 감정 분석(`AnalysisStore`)과 같은 구조다.
 */
@Component
class EmbeddingStore(
    private val embeddings: PostEmbeddingRepository,
    private val postApi: PostApi,
    private val properties: RecommendProperties,
    private val clock: Clock,
    aiProperties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 맡은 호출이 끝날 때까지 다른 실행기가 손대지 않을 시간: 호출 타임아웃 + 여유 10초. */
    private val lease: Duration = aiProperties.timeout.plus(LEASE_MARGIN)

    @Transactional
    fun request(
        postId: Long,
        authorId: Long,
    ) {
        embeddings.request(postId, authorId, now())
    }

    @Transactional
    fun remove(postId: Long) {
        embeddings.delete(postId)
    }

    /**
     * 시도할 차례인 행을 맡는다. [postId]를 주면 그 글만 본다. 맡을 것이 없으면 null이고, 기한이 지났거나 글이 지워져
     * 시도 없이 끝냈으면 [ClaimOutcome.Closed]다.
     */
    @Transactional
    fun claim(postId: Long? = null): ClaimOutcome? {
        val now = now()
        return embeddings.lockDue(now, postId)?.let { claimLocked(it, now) }
    }

    private fun claimLocked(
        claim: EmbeddingClaim,
        now: Instant,
    ): ClaimOutcome {
        val pastDeadline = now >= claim.requestedAt.plus(properties.retry.deadline)
        // 지운 글이면 보낼 본문이 없다. 숨긴 글은 작성자의 추천을 위해 그대로 처리한다
        val post = if (pastDeadline) null else postApi.find(claim.postId)
        if (post == null) {
            close(claim, now, pastDeadline)
            return ClaimOutcome.Closed
        }
        val delay = maxOf(properties.retry.delayAfter(claim.attempts + 1), lease)
        embeddings.markAttempt(claim.postId, now.plus(delay))
        return ClaimOutcome.Claimed(EmbeddingAttempt(claim, post.content))
    }

    /** 시도 없이 끝낸다. 기한이 지났으면 그만둠으로 남기고, 글이 지워졌으면 행을 지운다. */
    private fun close(
        claim: EmbeddingClaim,
        now: Instant,
        pastDeadline: Boolean,
    ) {
        if (pastDeadline) {
            log.warn("임베딩이 기한 안에 끝나지 않아 그만둡니다: postId={}, attempts={}", claim.postId, claim.attempts)
            embeddings.giveUp(claim.postId, now)
        } else {
            embeddings.delete(claim.postId)
        }
    }

    @Transactional
    fun recordSuccess(
        claim: EmbeddingClaim,
        model: String,
        values: FloatArray,
    ) {
        if (!embeddings.complete(claim, model, values, now())) {
            log.info("맡은 뒤 글이 고쳐져 임베딩 결과를 버립니다: postId={}", claim.postId)
        }
    }

    @Transactional
    fun recordFailure(
        claim: EmbeddingClaim,
        errorKind: String,
    ) {
        embeddings.recordFailure(claim, errorKind)
        log.info("임베딩 실패, 재시도 예약: postId={}, kind={}, attempts={}", claim.postId, errorKind, claim.attempts + 1)
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        val LEASE_MARGIN: Duration = Duration.ofSeconds(10)
    }
}

sealed interface ClaimOutcome {
    data class Claimed(
        val attempt: EmbeddingAttempt,
    ) : ClaimOutcome

    /** 기한이 지났거나 글이 지워져 시도 없이 끝냈다. */
    data object Closed : ClaimOutcome
}

/** 임베딩 시도 한 번: 맡기(짧은 트랜잭션) → 공급자 호출(트랜잭션 밖) → 결과 적기(짧은 트랜잭션). */
@Component
class EmbeddingRunner(
    private val store: EmbeddingStore,
    private val embedder: Embedder,
    private val properties: RecommendProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 글이 시도할 차례면 한 번 시도한다. 글이 저장된 직후 리스너가 부른다. */
    fun attempt(postId: Long) {
        (store.claim(postId) as? ClaimOutcome.Claimed)?.let { execute(it.attempt) }
    }

    /** 시도할 차례인 행을 `batch-size`개까지 하나씩 맡아 시도한다. 맡은 수를 돌려준다. */
    fun runDue(): Int {
        var handled = 0
        while (handled < properties.retry.batchSize) {
            val outcome = store.claim() ?: break
            if (outcome is ClaimOutcome.Claimed) execute(outcome.attempt)
            handled++
        }
        return handled
    }

    @Suppress("TooGenericExceptionCaught") // 임베더의 어떤 실패도 재시도 일정으로 넘긴다(constitution V)
    private fun execute(attempt: EmbeddingAttempt) {
        val claim = attempt.claim
        val embedding =
            try {
                embedder.embed("POST:${claim.postId}", attempt.content)
            } catch (e: EmbeddingFailed) {
                store.recordFailure(claim, e.kind.name)
                return
            } catch (e: RuntimeException) {
                log.warn("임베더가 예상하지 못한 예외를 던졌습니다: postId={}, cause={}", claim.postId, e.javaClass.simpleName)
                store.recordFailure(claim, EmotionAnalysisFailed.Kind.UPSTREAM_ERROR.name)
                return
            }
        store.recordSuccess(claim, embedding.model, embedding.values)
    }
}

/**
 * 글이 저장되거나 고쳐지면 커밋 뒤에 임베딩을 만든다(007 research R4). 리스너는 트랜잭션 없이 돈다. 공급자를 부르는 동안
 * DB 커넥션을 쥐지 않는다. 실패해도 리스너는 정상으로 끝나고 다시 시도하는 일은 스케줄러가 맡는다. 글이 지워지면 값을 지운다.
 */
@Component
class EmbeddingListener(
    private val store: EmbeddingStore,
    private val runner: EmbeddingRunner,
) {
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: PostWritten) {
        store.request(event.postId, event.authorId)
        runner.attempt(event.postId)
    }

    @ApplicationModuleListener
    fun on(event: PostRemoved) {
        store.remove(event.postId)
    }
}

/**
 * `poll-interval`(10초)마다 시도할 차례인 임베딩을 다시 시도한다. 이미 있는 글을 처리하는 일도 이 주기가 맡는다
 * (research R7). 여러 인스턴스가 돌아도 `FOR UPDATE SKIP LOCKED`와 맡을 때 미뤄 두는 다음 차례 덕분에 같은 행을 두 번
 * 맡지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.recommend.retry", name = ["scheduler-enabled"], matchIfMissing = true)
class EmbeddingRetryScheduler(
    private val runner: EmbeddingRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("TooGenericExceptionCaught") // 한 번 실패해도 다음 주기에 다시 돈다
    @Scheduled(fixedDelayString = "\${ogu.recommend.retry.poll-interval:10s}")
    fun poll() {
        try {
            val handled = runner.runDue()
            if (handled > 0) log.info("임베딩 처리: {}건", handled)
        } catch (e: RuntimeException) {
            log.error("임베딩 처리 주기가 실패했습니다", e)
        }
    }
}
