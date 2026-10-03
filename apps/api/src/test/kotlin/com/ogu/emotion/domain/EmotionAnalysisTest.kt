package com.ogu.emotion.domain

import com.ogu.emotion.AnalysisStatus
import com.ogu.emotion.EmotionAnalyzed
import com.ogu.emotion.EmotionType
import com.ogu.emotion.Intensity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * T016: 감정 분석 상태 전이와 재시도 간격(FR-004, research R2, data-model.md 상태 전이).
 */
class EmotionAnalysisTest {
    private val backoff = Backoff(initial = Duration.ofSeconds(30), maxInterval = Duration.ofMinutes(5))
    private val deadline = Duration.ofHours(24)
    private val createdAt = Instant.parse("2026-10-03T00:00:00Z")

    @Test
    fun `새 분석은 PENDING이고 시도 0번, 바로 시도할 수 있다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)

        assertThat(analysis.status).isEqualTo(AnalysisStatus.PENDING)
        assertThat(analysis.attempts).isZero()
        assertThat(analysis.nextAttemptAt).isEqualTo(createdAt)
        assertThat(analysis.emotion).isNull()
        assertThat(analysis.intensity).isNull()
    }

    @Test
    fun `PENDING에서 분석에 성공하면 ANALYZED가 되고 EmotionAnalyzed를 돌려준다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)
        val now = createdAt.plusSeconds(3)

        val event = analysis.succeed(EmotionType.ANXIETY, Intensity.HIGH, "발표 걱정", now)

        assertThat(event).isEqualTo(EmotionAnalyzed(POST_ID, EmotionType.ANXIETY, Intensity.HIGH, defaulted = false))
        assertThat(analysis.status).isEqualTo(AnalysisStatus.ANALYZED)
        assertThat(analysis.emotion).isEqualTo(EmotionType.ANXIETY)
        assertThat(analysis.intensity).isEqualTo(Intensity.HIGH)
        assertThat(analysis.reason).isEqualTo("발표 걱정")
        assertThat(analysis.attempts).isEqualTo(1)
        assertThat(analysis.completedAt).isEqualTo(now)
    }

    @Test
    fun `분류 근거는 200자에서 자른다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)

        analysis.succeed(EmotionType.ANXIETY, Intensity.LOW, "가".repeat(250), createdAt)

        assertThat(analysis.reason).hasSize(200)
    }

    @Test
    fun `PENDING에서 실패하면 PENDING에 머물고 시도 횟수와 다음 시각, 실패 분류가 남는다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)
        val now = createdAt.plusSeconds(2)

        val event = analysis.fail("TIMEOUT", now, backoff, deadline)

        assertThat(event).isNull()
        assertThat(analysis.status).isEqualTo(AnalysisStatus.PENDING)
        assertThat(analysis.attempts).isEqualTo(1)
        assertThat(analysis.nextAttemptAt).isEqualTo(now.plusSeconds(30))
        assertThat(analysis.lastError).isEqualTo("TIMEOUT")
        assertThat(analysis.completedAt).isNull()
    }

    @Test
    fun `실패할 때마다 간격이 30초에서 두 배씩 늘고 5분에서 멈춘다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)
        var now = createdAt
        val delays = mutableListOf<Long>()

        repeat(8) {
            analysis.fail("UPSTREAM_ERROR", now, backoff, deadline)
            delays += Duration.between(now, analysis.nextAttemptAt).seconds
            now = analysis.nextAttemptAt
        }

        assertThat(delays).containsExactly(30L, 60L, 120L, 240L, 300L, 300L, 300L, 300L)
        assertThat(analysis.attempts).isEqualTo(8)
    }

    @Test
    fun `백오프는 시도 횟수 1부터 정의되고 큰 횟수에도 넘치지 않는다`() {
        assertThat(backoff.delayAfter(1)).isEqualTo(Duration.ofSeconds(30))
        assertThat(backoff.delayAfter(5)).isEqualTo(Duration.ofMinutes(5))
        assertThat(backoff.delayAfter(10_000)).isEqualTo(Duration.ofMinutes(5))
        assertThatThrownBy { backoff.delayAfter(0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `24시간이 되기 1초 전(23시간 59분 59초)에 실패하면 아직 재시도한다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)
        val now = createdAt.plus(deadline).minusSeconds(1)

        val event = analysis.fail("TIMEOUT", now, backoff, deadline)

        assertThat(event).isNull()
        assertThat(analysis.status).isEqualTo(AnalysisStatus.PENDING)
        assertThat(analysis.isPastDeadline(now, deadline)).isFalse()
    }

    @Test
    fun `기한 직전에 실패하면 다음 시각은 24시간 정각을 넘지 않는다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)
        analysis.fail("TIMEOUT", createdAt, backoff, deadline)

        analysis.fail("TIMEOUT", createdAt.plus(deadline).minusSeconds(1), backoff, deadline)
        assertThat(analysis.nextAttemptAt).isEqualTo(createdAt.plus(deadline))

        analysis.claim(createdAt.plus(deadline).minusSeconds(10), backoff, deadline)
        assertThat(analysis.nextAttemptAt).isEqualTo(createdAt.plus(deadline))
    }

    @Test
    fun `24시간 정각(24시간 0분 0초)에 실패하면 무기력, 낮음 기본값으로 DEFAULTED가 된다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)
        val now = createdAt.plus(deadline)

        val event = analysis.fail("TIMEOUT", now, backoff, deadline)

        assertThat(event).isEqualTo(EmotionAnalyzed(POST_ID, EmotionType.LETHARGY, Intensity.LOW, defaulted = true))
        assertThat(analysis.status).isEqualTo(AnalysisStatus.DEFAULTED)
        assertThat(analysis.emotion).isEqualTo(EmotionType.LETHARGY)
        assertThat(analysis.intensity).isEqualTo(Intensity.LOW)
        assertThat(analysis.attempts).isEqualTo(1)
        assertThat(analysis.lastError).isEqualTo("TIMEOUT")
        assertThat(analysis.completedAt).isEqualTo(now)
    }

    @Test
    fun `기한이 지난 PENDING은 시도 없이 기본값으로 끝낼 수 있다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)
        val now = createdAt.plus(deadline)
        assertThat(analysis.isPastDeadline(now, deadline)).isTrue()

        val event = analysis.expire(now)

        assertThat(event.defaulted).isTrue()
        assertThat(analysis.status).isEqualTo(AnalysisStatus.DEFAULTED)
        assertThat(analysis.attempts).isZero()
    }

    @Test
    fun `시도를 맡으면 다음 시각을 이번 시도가 실패했을 때의 간격만큼 미뤄 둔다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)
        analysis.fail("TIMEOUT", createdAt, backoff, deadline)
        val now = createdAt.plusSeconds(30)

        analysis.claim(now, backoff, deadline)

        // 실행기가 호출 중에 죽어도 이 시각에 다른 실행기가 다시 시도한다. 시도 횟수는 결과를 기록할 때만 는다.
        assertThat(analysis.nextAttemptAt).isEqualTo(now.plusSeconds(60))
        assertThat(analysis.attempts).isEqualTo(1)
        assertThat(analysis.status).isEqualTo(AnalysisStatus.PENDING)
    }

    @Test
    fun `끝난 분석은 다시 성공, 실패, 기본값으로 바꿀 수 없다`() {
        val analysis = EmotionAnalysis.pending(POST_ID, createdAt, now = createdAt)
        analysis.succeed(EmotionType.IRRITATION, Intensity.MEDIUM, "화남", createdAt)

        assertThatThrownBy { analysis.succeed(EmotionType.ANXIETY, Intensity.LOW, "x", createdAt) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { analysis.fail("TIMEOUT", createdAt, backoff, deadline) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { analysis.expire(createdAt) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { analysis.claim(createdAt, backoff, deadline) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    companion object {
        private const val POST_ID = 42L
    }
}
