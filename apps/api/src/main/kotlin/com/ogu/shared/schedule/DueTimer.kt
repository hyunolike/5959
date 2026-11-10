package com.ogu.shared.schedule

import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 작업 하나의 다음 시각을 들고 있다가 그때 [DueJob.runDue]를 부른다(018 research R3).
 *
 * - [arm]은 지금 걸린 시각보다 이를 때만 다시 건다. 늦은 시각은 무시한다(돌고 나면 [DueJob.nextDue]가 다시 정한다).
 * - 돌고 나면 언제나 [rearm]으로 다음 시각을 DB에서 다시 읽는다. 도는 동안 들어온 [arm]도 잃지 않는다.
 * - [DueJob.nextDue]가 실패하면(DB에 닿지 못함) [retryOnError] 뒤에 다시 읽는다.
 * - 한 작업은 한 번에 하나만 돈다.
 * - 돌고 난 뒤 [minGap] 안에는 다시 돌지 않는다. 일이 실패해 다음 시각이 계속 지난 시각으로 남아도
 *   쉬지 않고 DB를 두드리지 않게 하려는 것이다. 밀린 일은 [minGap]마다 한 묶음씩 처리된다.
 */
class DueTimer internal constructor(
    private val job: DueJob,
    private val clock: Clock,
    private val scheduler: DelayScheduler,
    private val retryOnError: Duration,
    private val minGap: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()
    private var armedAt: Instant? = null
    private var handle: Cancellable? = null
    private var running = false
    private var pendingAt: Instant? = null
    private var notBefore: Instant? = null

    /** 지금 걸린 다음 시각. 테스트와 로그용이다. */
    val nextAt: Instant?
        get() = lock.withLock { armedAt }

    /** [requested]에 돌도록 건다. 지금 걸린 시각이 더 이르면 그대로 둔다. 직전에 돌았다면 [minGap]이 지난 뒤로 미룬다. */
    fun arm(requested: Instant) {
        lock.withLock {
            if (running) {
                pendingAt = earlier(pendingAt, requested)
                return
            }
            val at = notBefore?.takeIf { requested.isBefore(it) } ?: requested
            val current = armedAt
            if (current != null && !at.isBefore(current)) return
            handle?.cancel()
            armedAt = at
            val delay = Duration.between(clock.instant(), at).coerceAtLeast(Duration.ZERO)
            handle = scheduler.schedule(delay) { fire() }
        }
    }

    /** 트랜잭션 안이면 커밋된 뒤에, 아니면 바로 [arm]. 롤백되면 걸지 않는다. */
    fun armAfterCommit(at: Instant) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            arm(at)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = arm(at)
            },
        )
    }

    /** 걸린 시각을 버리고 [DueJob.nextDue]로 다시 정한다. */
    @Suppress("TooGenericExceptionCaught") // DB에 닿지 못해도 타이머는 살아 있어야 한다
    fun rearm() {
        val next =
            try {
                job.nextDue()
            } catch (e: RuntimeException) {
                log.warn("{}: 다음 시각을 읽지 못해 {} 뒤에 다시 봅니다 ({})", job.name, retryOnError, e.toString())
                clock.instant().plus(retryOnError)
            }
        lock.withLock {
            if (running) {
                next?.let { pendingAt = earlier(pendingAt, it) }
                return
            }
            handle?.cancel()
            handle = null
            armedAt = null
        }
        next?.let(::arm)
    }

    @Suppress("TooGenericExceptionCaught") // 한 번 실패해도 다음 시각은 다시 건다
    private fun fire() {
        lock.withLock {
            armedAt = null
            handle = null
            running = true
        }
        try {
            job.runDue()
        } catch (e: RuntimeException) {
            log.error("{}: 차례가 된 일을 하다 실패했습니다", job.name, e)
        } finally {
            val pending =
                lock.withLock {
                    running = false
                    notBefore = clock.instant().plus(minGap)
                    pendingAt.also { pendingAt = null }
                }
            rearm()
            pending?.let(::arm)
        }
    }

    internal fun cancel() {
        lock.withLock {
            handle?.cancel()
            handle = null
            armedAt = null
        }
    }

    private fun earlier(
        a: Instant?,
        b: Instant,
    ): Instant = if (a == null || b.isBefore(a)) b else a
}
