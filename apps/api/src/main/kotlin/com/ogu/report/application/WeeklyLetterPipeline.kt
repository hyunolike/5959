package com.ogu.report.application

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.WeeklyLetterFailed
import com.ogu.ai.WeeklyLetterInput
import com.ogu.ai.WeeklyLetterWriter
import com.ogu.emotion.EmotionType
import com.ogu.report.WeeklyReportPublished
import com.ogu.report.domain.WeeklyLetterRepository
import com.ogu.report.domain.WeeklyReport
import com.ogu.report.domain.WeeklyReportRepository
import com.ogu.shared.config.AiProperties
import com.ogu.shared.text.ContentMask
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
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 맡은 리포트와 공급자에 보낼 수치. */
data class LetterAttempt(
    val reportId: Long,
    val attempts: Int,
    val input: WeeklyLetterInput,
)

/**
 * 편지의 처리 일정을 다루는 짧은 트랜잭션들(008 research R6). 공급자 호출은 이 트랜잭션들 밖에서 한다. 맡기 → 커밋 →
 * 호출 → 결과 적기 순서다. 007의 `EmbeddingStore`와 같은 구조다.
 */
@Component
class WeeklyLetterStore(
    private val letters: WeeklyLetterRepository,
    private val reports: WeeklyReportRepository,
    private val properties: ReportProperties,
    private val clock: Clock,
    aiProperties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 맡은 호출이 끝날 때까지 다른 실행기가 손대지 않을 시간: 호출 타임아웃 + 여유 10초. */
    private val lease: Duration = aiProperties.timeout.plus(LEASE_MARGIN)

    /**
     * 편지를 쓸 차례인 리포트를 맡는다. [memberId]와 [weekStart]를 주면 그 리포트만 본다. 맡을 것이 없으면 null이고,
     * 기한이 지나 시도 없이 닫았으면 [LetterClaim.Closed]다.
     */
    @Transactional
    fun claim(
        memberId: Long? = null,
        weekStart: LocalDate? = null,
    ): LetterClaim? {
        val now = now()
        return letters.lockDue(now, memberId, weekStart)?.let { claimLocked(it, now) }
    }

    private fun claimLocked(
        report: WeeklyReport,
        now: Instant,
    ): LetterClaim {
        if (now >= report.publishedAt.plus(properties.letter.deadline)) {
            log.warn("편지를 기한 안에 쓰지 못해 편지 없이 닫습니다: reportId={}, attempts={}", report.id, report.letterAttempts)
            letters.giveUp(report.id)
            return LetterClaim.Closed
        }
        val delay = maxOf(properties.letter.delayAfter(report.letterAttempts + 1), lease)
        letters.markAttempt(report.id, now.plus(delay))
        return LetterClaim.Claimed(LetterAttempt(report.id, report.letterAttempts, inputOf(report)))
    }

    /** 공급자에 보낼 것은 이 리포트의 수치와, 있으면 앞 주의 글 수와 가장 많은 감정뿐이다(FR-010). */
    private fun inputOf(report: WeeklyReport): WeeklyLetterInput {
        val previous = reports.find(report.memberId, WeekRange(report.weekStart).previous().start)
        val stats = report.stats
        return WeeklyLetterInput(
            postCount = stats.postCount,
            emotionCounts = stats.emotionCounts.mapKeys { classified(it.key) },
            unanalyzedCount = stats.unanalyzedCount,
            topEmotion = stats.topEmotion?.let(::classified),
            defeatedCount = stats.defeatedCount,
            receivedLikes = stats.receivedLikes,
            receivedComments = stats.receivedComments,
            previousPostCount = previous?.stats?.postCount,
            previousTopEmotion = previous?.stats?.topEmotion?.let(::classified),
        )
    }

    private fun classified(emotion: EmotionType): ClassifiedEmotion = ClassifiedEmotion.valueOf(emotion.name)

    @Transactional
    fun recordSuccess(
        attempt: LetterAttempt,
        letter: String,
    ) {
        if (!letters.complete(attempt.reportId, letter)) {
            log.info("맡은 뒤 닫힌 리포트라 편지를 버립니다: reportId={}", attempt.reportId)
        }
    }

    @Transactional
    fun recordFailure(
        attempt: LetterAttempt,
        errorKind: String,
    ) {
        letters.recordFailure(attempt.reportId, errorKind)
        val attempts = attempt.attempts + 1
        log.info("편지 쓰기 실패, 재시도 예약: reportId={}, kind={}, attempts={}", attempt.reportId, errorKind, attempts)
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        val LEASE_MARGIN: Duration = Duration.ofSeconds(10)
    }
}

sealed interface LetterClaim {
    data class Claimed(
        val attempt: LetterAttempt,
    ) : LetterClaim

    /** 기한이 지나 시도 없이 닫았다. */
    data object Closed : LetterClaim
}

/** 편지 쓰기 한 번: 맡기(짧은 트랜잭션) → 공급자 호출(트랜잭션 밖) → 결과 적기(짧은 트랜잭션). */
@Component
class WeeklyLetterRunner(
    private val store: WeeklyLetterStore,
    private val writer: WeeklyLetterWriter,
    private val contentMask: ContentMask,
    private val properties: ReportProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 리포트가 편지를 쓸 차례면 한 번 시도한다. 리포트가 발행된 직후 리스너가 부른다. */
    fun attempt(
        memberId: Long,
        weekStart: LocalDate,
    ) {
        (store.claim(memberId, weekStart) as? LetterClaim.Claimed)?.let { execute(it.attempt) }
    }

    /** 차례가 된 리포트를 `batch-size`개까지 하나씩 맡아 시도한다. 맡은 수를 돌려준다. */
    fun runDue(): Int {
        var handled = 0
        while (handled < properties.letter.batchSize) {
            val claim = store.claim() ?: break
            if (claim is LetterClaim.Claimed) execute(claim.attempt)
            handled++
        }
        return handled
    }

    @Suppress("TooGenericExceptionCaught") // 편지 쓰기의 어떤 실패도 재시도 일정으로 넘긴다(constitution V)
    private fun execute(attempt: LetterAttempt) {
        val letter =
            try {
                checked(writer.write("REPORT:${attempt.reportId}", attempt.input))
            } catch (e: WeeklyLetterFailed) {
                store.recordFailure(attempt, e.kind.name)
                return
            } catch (e: RuntimeException) {
                log.warn("편지 쓰기가 예상하지 못한 예외를 던졌습니다: reportId={}, cause={}", attempt.reportId, e.javaClass.simpleName)
                store.recordFailure(attempt, EmotionAnalysisFailed.Kind.UPSTREAM_ERROR.name)
                return
            }
        store.recordSuccess(attempt, letter)
    }

    /** AI가 쓴 글이라 회원에게 보이기 전에 한 번 더 본다. 가려질 낱말이 있으면 편지로 쓰지 않는다. */
    private fun checked(letter: String): String {
        if (contentMask.mask(letter) != letter) throw WeeklyLetterFailed(EmotionAnalysisFailed.Kind.INVALID_RESPONSE)
        return letter
    }
}

/**
 * 리포트가 발행되면 커밋 뒤에 편지를 한 번 써 본다. 리스너는 트랜잭션 없이 돈다. 공급자를 부르는 동안 DB 커넥션을 쥐지
 * 않는다. 실패해도 리스너는 정상으로 끝나고 다시 시도하는 일은 스케줄러가 맡는다. 위기 글이 있던 주의 리포트는 처음부터
 * 맡을 것이 없다.
 */
@Component
class WeeklyLetterListener(
    private val runner: WeeklyLetterRunner,
) {
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: WeeklyReportPublished) {
        runner.attempt(event.memberId, event.weekStart)
    }
}

/**
 * `letter.poll-interval`(10초)마다 차례가 된 편지를 다시 시도한다. 여러 인스턴스가 돌아도 `FOR UPDATE SKIP LOCKED`와 맡을
 * 때 미뤄 두는 다음 차례 덕분에 같은 리포트를 두 번 맡지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.report", name = ["scheduler-enabled"], matchIfMissing = true)
class WeeklyLetterRetryScheduler(
    private val runner: WeeklyLetterRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("TooGenericExceptionCaught") // 한 번 실패해도 다음 주기에 다시 돈다
    @Scheduled(fixedDelayString = "\${ogu.report.letter.poll-interval:10s}")
    fun poll() {
        try {
            val handled = runner.runDue()
            if (handled > 0) log.info("편지 처리: {}건", handled)
        } catch (e: RuntimeException) {
            log.error("편지 처리 주기가 실패했습니다", e)
        }
    }
}
