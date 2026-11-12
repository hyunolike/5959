package com.ogu.recommend.application

import com.ogu.ai.Embedder
import com.ogu.post.PostSelectionApi
import com.ogu.recommend.domain.EmbeddingBackfillRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 추천이 생기기 전에 쓰인 글을 임베딩 대상에 올린다(007 US4, research R7). 여기서는 기다리는 행만 만들고, 실제 처리는
 * 재시도 스케줄러가 한 번에 정해진 수만큼 한다. 새로 쓴 글은 저장 직후 바로 시도하므로 밀리지 않는다.
 *
 * 글마다의 진행 표지는 두지 않는다. "행이 없는 글"이 곧 남은 일이라, 중간에 멈춰도 다시 뜨면 이어서 하고 이미 처리한
 * 글은 건드리지 않는다. 모델이 바뀌었으면 옛 모델로 만든 값도 다시 만들게 한다(FR-015).
 *
 * 다만 서버가 아무 때나 다시 뜨므로(Render), 기동 때마다 모든 글을 훑지 않게 모델별 완료 표지(`embedding_backfill`)를
 * 남긴다(018 research R3). 기동 때는 [runUnlessCompleted]를 부른다. 모델을 바꾸면 새 이름의 표지가 없으므로 한 번 훑는다.
 */
@Component
class EmbeddingBackfill(
    private val embeddings: EmbeddingBackfillRepository,
    private val posts: PostSelectionApi,
    private val embedder: Embedder,
    private val transaction: TransactionTemplate,
    private val properties: RecommendProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 지금 모델로 훑기를 마친 적이 없을 때만 [run]하고, 끝까지 마치면 표지를 남긴다. 이미 마쳤으면 훑지 않고 null이다.
     * 훑는 도중 죽으면 표지가 없으므로 다음 기동 때 처음부터 다시 훑는다(이미 행이 있는 글은 건너뛰므로 결과는 같다).
     */
    fun runUnlessCompleted(): Int? {
        val model = embedder.model
        if (embeddings.isCompleted(model)) return null
        val queued = run()
        embeddings.markCompleted(model, clock.instant().truncatedTo(ChronoUnit.MICROS))
        return queued
    }

    /** 행이 없는 글에 기다리는 행을 만들고, 옛 모델의 행을 다시 기다리게 한다. 올린 수를 돌려준다. */
    fun run(): Int {
        val queued = queueMissing() + requeueOtherModels()
        if (queued > 0) log.info("Queued {} existing posts for embedding", queued)
        return queued
    }

    private fun queueMissing(): Int {
        val batchSize = properties.backfill.batchSize
        var afterId = 0L
        var queued = 0
        do {
            val batch = posts.authorsAfter(afterId, batchSize)
            if (batch.isEmpty()) break
            queued +=
                transaction.execute {
                    val existing = embeddings.existingIds(batch.map { it.postId })
                    val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                    batch
                        .filterNot { it.postId in existing }
                        .sumOf { embeddings.requestIfAbsent(it.postId, it.authorId, now) }
                } ?: 0
            afterId = batch.last().postId
        } while (batch.size >= batchSize)
        return queued
    }

    private fun requeueOtherModels(): Int {
        val batchSize = properties.backfill.batchSize
        var total = 0
        do {
            val count =
                transaction.execute {
                    val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                    embeddings.requeueOtherModels(embedder.model, now, batchSize)
                } ?: 0
            total += count
        } while (count >= batchSize)
        return total
    }
}

/**
 * 떠오른 뒤 따로 도는 스레드에서 시작한다. 요청을 받는 일을 늦추지 않는다. 실패해도 서비스는 그대로이고 다음에 뜰 때 다시
 * 한다. 지금 모델로 이미 마쳤으면 훑지 않는다. `ogu.recommend.backfill.enabled=false`면 돌지 않는다.
 *
 * 올린 행은 커밋됐으므로 끝나면 재시도 타이머를 다시 건다(018 research R3). 스케줄러를 끈 테스트에서는 타이머가 없다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.recommend.backfill", name = ["enabled"], matchIfMissing = true)
class EmbeddingBackfillStarter(
    private val backfill: EmbeddingBackfill,
    private val retry: ObjectProvider<EmbeddingRetryScheduler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        Thread({ runSafely() }, "embedding-backfill").apply { isDaemon = true }.start()
    }

    @Suppress("TooGenericExceptionCaught") // 어떤 실패든 로그만 남기고 다음 기동에 다시 한다
    private fun runSafely() {
        try {
            if (backfill.runUnlessCompleted() == null) log.info("이미 있는 글 훑기는 지금 모델로 마쳐 건너뜁니다")
        } catch (e: RuntimeException) {
            log.error("이미 있는 글을 임베딩 대상에 올리지 못했습니다. 다음에 뜰 때 다시 합니다: {}", e.javaClass.simpleName)
        } finally {
            retry.ifAvailable { it.rearm() }
        }
    }
}
