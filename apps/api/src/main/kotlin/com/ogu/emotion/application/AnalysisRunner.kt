package com.ogu.emotion.application

import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.EmotionAnalyzer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 감정 분석 시도 한 번(research R2): 맡기(짧은 트랜잭션, 커밋) → LLM 호출(트랜잭션 밖) → 결과 기록(짧은 트랜잭션).
 * 성공하면 ANALYZED, 실패하면 다음 시각, 기한이 지났으면 DEFAULTED가 되고, 끝났으면 `EmotionAnalyzed`가 발행된다.
 */
@Component
class AnalysisRunner(
    private val store: AnalysisStore,
    private val analyzer: EmotionAnalyzer,
    private val properties: EmotionRetryProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 글의 분석이 시도할 차례면 한 번 시도한다. 글 작성 직후 리스너가 부른다. */
    fun attempt(postId: Long) {
        store.claim(postId)?.let(::execute)
    }

    /** 시도할 차례인 분석을 최대 `batch-size`개까지 하나씩 맡아 시도한다. 맡은 개수(기본값으로 끝낸 것 포함)를 돌려준다. */
    fun runDue(): Int {
        var handled = 0
        while (handled < properties.batchSize) {
            val result = store.claimNextDue() ?: break
            if (result is ClaimResult.Claimed) execute(result.claim)
            handled++
        }
        return handled
    }

    @Suppress("TooGenericExceptionCaught") // 분석기의 어떤 실패도 재시도 일정으로 넘긴다(constitution V)
    private fun execute(claim: AnalysisClaim) {
        val classification =
            try {
                analyzer.analyze(claim.postId, claim.content)
            } catch (e: EmotionAnalysisFailed) {
                store.recordFailure(claim, e.kind.name)
                return
            } catch (e: RuntimeException) {
                log.warn("감정 분석기가 예상하지 못한 예외를 던졌습니다: postId={}, cause={}", claim.postId, e.javaClass.simpleName)
                store.recordFailure(claim, EmotionAnalysisFailed.Kind.UPSTREAM_ERROR.name)
                return
            }
        store.recordSuccess(claim, classification)
    }
}
