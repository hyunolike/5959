package com.ogu.notification.application

import com.ogu.notification.domain.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 보관 기간 정리(FR-010, research R10). 매일 04:00(한국 시간)에 만든 지 90일이 지난 알림을
 * [NotificationProperties.purgeBatchSize](1,000)행씩 지운다. 공감 참여자 행은 FK `ON DELETE CASCADE`로 함께 지워진다.
 * 이어서 끝난 지 7일이 지난 이벤트 발행(`event_publication`)을 지운다. 끝나지 않은 발행은 재전송 대상이라 건드리지 않는다.
 *
 * - 두 단계는 따로 실패한다. 한쪽이 실패해도 다른 쪽은 돌고, 실패마다 ERROR를 남긴 뒤 처음 실패를 다시 던져 스케줄러도
 *   실패로 본다(둘 다 실패하면 나중 것은 `suppressed`로 붙는다).
 * - 화면의 보관 기간은 읽는 쿼리의 조건이 지킨다. 이 작업은 공간만 돌려주므로 늦거나 한 번 걸러도 보이는 것은 같다.
 * - 한 문장이 한 묶음만 지우고 바로 커밋한다(메서드에 트랜잭션을 두지 않는다). 긴 잠금이나 큰 트랜잭션이 생기지 않는다.
 * - 인스턴스가 둘이어도 ShedLock 없이 둔다. 지우기는 멱등이고, 다른 쪽이 잡고 있는 행은 건너뛴다(`SKIP LOCKED`).
 *   건너뛴 탓에 묶음이 덜 차면 거기서 끝내고 남은 것은 다른 쪽이나 다음 날이 지운다.
 */
@Component
class NotificationPurgeJob(
    private val notifications: NotificationRepository,
    private val completedPublications: CompletedEventPublications,
    private val properties: NotificationProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${ogu.notification.purge-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    fun purge(): PurgeResult {
        val purged = step("알림 정리") { purgeNotifications() }
        val cleaned = step("끝난 이벤트 발행 정리") { purgePublications() }
        val failure = purged.exceptionOrNull()
        val later = cleaned.exceptionOrNull()
        if (failure != null) {
            if (later != null) failure.addSuppressed(later)
            throw failure
        }
        if (later != null) throw later
        return purged.getOrThrow()
    }

    /** 한 단계를 돌린다. 실패하면 ERROR를 남기고 결과로 돌려줘, 다음 단계가 돌 수 있게 한다. */
    @Suppress("TooGenericExceptionCaught") // 어떤 실패든 다른 단계를 막지 않게 받아 두고, 끝에서 다시 던진다
    private fun <T> step(
        name: String,
        block: () -> T,
    ): Result<T> =
        try {
            Result.success(block())
        } catch (e: RuntimeException) {
            log.error("{}에 실패했습니다", name, e)
            Result.failure(e)
        }

    private fun purgePublications() {
        completedPublications.deletePublicationsOlderThan(PUBLICATION_RETENTION)
        log.info("Deleted completed event publications older than {} days", PUBLICATION_RETENTION.toDays())
    }

    private fun purgeNotifications(): PurgeResult {
        val batchSize = properties.purgeBatchSize
        var deleted = 0
        var batches = 0
        do {
            val count = notifications.deleteExpired(batchSize)
            deleted += count
            batches++
        } while (count >= batchSize)
        log.info("Deleted {} expired notification rows in {} batches", deleted, batches)
        return PurgeResult(notifications = deleted, batches = batches)
    }

    companion object {
        private val PUBLICATION_RETENTION: Duration = Duration.ofDays(7)
    }
}

/** 정리 한 번의 결과. [notifications]는 지운 알림 수, [batches]는 지우는 문장을 돌린 횟수다. */
data class PurgeResult(
    val notifications: Int,
    val batches: Int,
)
