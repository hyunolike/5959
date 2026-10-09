package com.ogu.safety.application

import com.ogu.post.ContentType
import com.ogu.post.ModerationTarget
import com.ogu.post.PostModerationApi
import com.ogu.safety.RiskDetected
import com.ogu.safety.RiskLevel
import com.ogu.safety.domain.SafetyBackfillRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 안전 기능이 생기기 전에 쓰인 글과 댓글을 키워드 규칙으로 한 번 훑는다(005 research R14). 위기면 숨기고 작성자에게
 * 도움 안내를 보낸다. 우려는 기록하고 작성자의 화면에만 안내를 보인다. AI 분류는 예약하지 않는다(양과 비용).
 *
 * 묶음마다 트랜잭션 하나다. 진행 표지(`safety_backfill.last_id`)를 묶음과 함께 커밋하므로 중간에 멈춰도 다음에 이어서
 * 하고, 끝난 종류는 다시 돌지 않는다. 묶음마다 advisory lock을 잡아 인스턴스가 둘이어도 한쪽만 돈다.
 * 이미 판정 기록이 있는 대상(출시 뒤에 쓰인 글)은 건너뛴다.
 */
@Component
class SafetyBackfill(
    private val batch: SafetyBackfillBatch,
    private val properties: SafetyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 남은 것을 끝까지 훑는다. 다른 인스턴스가 맡고 있으면 그 종류는 넘긴다. */
    fun run(): BackfillResult {
        val scanned = ContentType.entries.sumOf { type -> runType(type) }
        return BackfillResult(scanned)
    }

    private fun runType(type: ContentType): Int {
        var total = 0
        do {
            val count = batch.run(type)
            if (count > 0) total += count
        } while (count >= properties.backfill.batchSize)
        if (total > 0) log.info("Screened {} existing {} rows", total, type)
        return total
    }
}

/** 훑기의 한 묶음. 판정, 숨김, 진행 표지가 한 트랜잭션으로 커밋된다. */
@Component
class SafetyBackfillBatch(
    private val backfills: SafetyBackfillRepository,
    private val moderation: PostModerationApi,
    private val termCache: TermCache,
    private val events: ApplicationEventPublisher,
    private val properties: SafetyProperties,
    private val clock: Clock,
) {
    /** 한 묶음을 훑고 읽은 수를 돌려준다. 끝났거나 다른 인스턴스가 맡고 있으면 [STOP]이다. */
    @Transactional
    fun run(type: ContentType): Int {
        val lastId = backfills.takeIf { it.tryLock() }?.progress(type) ?: return STOP
        val batchSize = properties.backfill.batchSize
        val targets = moderation.scan(type, lastId, batchSize)
        val assessed = backfills.assessedIds(type, targets.map { it.id })
        val terms = termCache.terms()
        targets.filterNot { it.id in assessed }.forEach { screen(it, KeywordRule.level(it.content, terms)) }

        val finished = targets.size < batchSize
        backfills.advance(type, targets.lastOrNull()?.id ?: lastId, now().takeIf { finished })
        return targets.size
    }

    private fun screen(
        target: ModerationTarget,
        level: RiskLevel,
    ) {
        if (level == RiskLevel.NONE) return
        backfills.insertKeywordOnly(target, level, now())
        moderation.markRisk(target.type, target.id, level.name)
        if (level == RiskLevel.CRISIS) {
            moderation.hide(target.type, target.id, ScreeningListener.HIDDEN_BY_RISK)
            events.publishEvent(RiskDetected(target.type, target.id, target.postId, target.authorId, level))
        }
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val STOP = -1
    }
}

/** 훑기 한 번에 읽은 글과 댓글 수. */
data class BackfillResult(
    val scanned: Int,
)

/**
 * 떠오른 뒤 훑기를 따로 도는 스레드에서 시작한다. 요청을 받는 일을 늦추지 않는다. 실패해도 서비스는 그대로이고 다음에 뜰 때
 * 이어서 한다. `ogu.safety.backfill.enabled=false`면 돌지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.safety.backfill", name = ["enabled"], matchIfMissing = true)
class SafetyBackfillStarter(
    private val backfill: SafetyBackfill,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        Thread({ runSafely() }, "safety-backfill").apply { isDaemon = true }.start()
    }

    @Suppress("TooGenericExceptionCaught") // 어떤 실패든 로그만 남기고 다음 기동에 이어서 한다
    private fun runSafely() {
        try {
            backfill.run()
        } catch (e: RuntimeException) {
            // 본문은 남기지 않는다
            log.error("이미 있는 글 훑기에 실패했습니다. 다음에 뜰 때 이어서 합니다: {}", e.javaClass.simpleName)
        }
    }
}
