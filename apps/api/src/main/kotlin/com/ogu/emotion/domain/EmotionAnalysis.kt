package com.ogu.emotion.domain

import com.ogu.emotion.AnalysisStatus
import com.ogu.emotion.EmotionAnalyzed
import com.ogu.emotion.EmotionType
import com.ogu.emotion.Intensity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.Duration
import java.time.Instant

/**
 * 글 하나의 감정 분석 상태(data-model.md `emotion_analysis`). 상태 전이는 이 클래스의 메서드로만 한다.
 *
 * - [succeed]: PENDING → ANALYZED
 * - [fail]: PENDING → PENDING(시도 횟수 +1, 다음 시각 = min(지금 + [Backoff], 기한)), 기한이 지났으면 PENDING → DEFAULTED
 * - [expire]: 기한이 지난 PENDING → DEFAULTED(시도 없이)
 *
 * ANALYZED나 DEFAULTED가 되는 메서드는 같은 트랜잭션에서 발행할 [EmotionAnalyzed]를 돌려준다.
 */
@Entity
@Table(name = "emotion_analysis")
class EmotionAnalysis private constructor(
    @Id
    @Column(name = "post_id")
    val postId: Long,
    @Column(name = "post_created_at", nullable = false, updatable = false)
    val postCreatedAt: Instant,
    nextAttemptAt: Instant,
) : Persistable<Long> {
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    var status: AnalysisStatus = AnalysisStatus.PENDING
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "emotion", length = 20)
    var emotion: EmotionType? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "intensity", length = 10)
    var intensity: Intensity? = null
        protected set

    @Column(name = "reason", length = REASON_MAX_LENGTH)
    var reason: String? = null
        protected set

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0
        protected set

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = nextAttemptAt
        protected set

    @Column(name = "last_error", length = 200)
    var lastError: String? = null
        protected set

    @Column(name = "completed_at")
    var completedAt: Instant? = null
        protected set

    @Transient
    private var isNew: Boolean = true

    override fun getId(): Long = postId

    override fun isNew(): Boolean = isNew

    /** 글을 쓴 뒤 [deadline]이 지났는가(경계 포함: 정확히 24시간이면 지났다). */
    fun isPastDeadline(
        now: Instant,
        deadline: Duration,
    ): Boolean = !now.isBefore(postCreatedAt.plus(deadline))

    /**
     * 실행기가 이번 시도를 맡는다. 다음 시각을 "이번 시도가 실패했을 때의 다음 시각"으로 미리 미뤄 두므로, 호출 중에
     * 실행기가 죽어도 그 시각이 되면 다른 실행기가 다시 맡는다. 시도 횟수는 결과를 기록할 때만 는다.
     */
    fun claim(
        now: Instant,
        backoff: Backoff,
        deadline: Duration,
    ) {
        checkPending()
        // 기한을 넘기지 않는다. 기한 직전에 맡거나 실패해도 정확히 24시간이 되면 기본값으로 끝낼 수 있다.
        nextAttemptAt = minOf(now.plus(backoff.delayAfter(attempts + 1)), postCreatedAt.plus(deadline))
    }

    fun succeed(
        emotion: EmotionType,
        intensity: Intensity,
        reason: String,
        now: Instant,
    ): EmotionAnalyzed {
        checkPending()
        attempts += 1
        this.reason = reason.take(REASON_MAX_LENGTH)
        return complete(AnalysisStatus.ANALYZED, emotion, intensity, now)
    }

    /** 실패를 기록한다. 기한 안이면 다음 시각을 잡고 null을, 기한이 지났으면 기본값으로 끝내고 이벤트를 돌려준다. */
    fun fail(
        errorKind: String,
        now: Instant,
        backoff: Backoff,
        deadline: Duration,
    ): EmotionAnalyzed? {
        checkPending()
        attempts += 1
        lastError = errorKind.take(LAST_ERROR_MAX_LENGTH)
        if (isPastDeadline(now, deadline)) {
            return complete(AnalysisStatus.DEFAULTED, DEFAULT_EMOTION, DEFAULT_INTENSITY, now)
        }
        nextAttemptAt = minOf(now.plus(backoff.delayAfter(attempts)), postCreatedAt.plus(deadline))
        return null
    }

    /** 기한이 지나 시도 없이 기본값(무기력, 낮음)으로 끝낸다. */
    fun expire(now: Instant): EmotionAnalyzed {
        checkPending()
        return complete(AnalysisStatus.DEFAULTED, DEFAULT_EMOTION, DEFAULT_INTENSITY, now)
    }

    private fun complete(
        status: AnalysisStatus,
        emotion: EmotionType,
        intensity: Intensity,
        now: Instant,
    ): EmotionAnalyzed {
        this.status = status
        this.emotion = emotion
        this.intensity = intensity
        completedAt = now
        return EmotionAnalyzed(postId, emotion, intensity, defaulted = status == AnalysisStatus.DEFAULTED)
    }

    private fun checkPending() {
        check(status == AnalysisStatus.PENDING) { "이미 끝난 분석입니다(postId=$postId, status=$status)." }
    }

    @Suppress("UnusedPrivateMember") // JPA 콜백으로 Hibernate가 호출한다
    @PostLoad
    @PostPersist
    private fun markNotNew() {
        isNew = false
    }

    companion object {
        const val REASON_MAX_LENGTH = 200
        private const val LAST_ERROR_MAX_LENGTH = 200
        val DEFAULT_EMOTION = EmotionType.LETHARGY
        val DEFAULT_INTENSITY = Intensity.LOW

        fun pending(
            postId: Long,
            postCreatedAt: Instant,
            now: Instant,
        ): EmotionAnalysis = EmotionAnalysis(postId, postCreatedAt, nextAttemptAt = now)
    }
}
