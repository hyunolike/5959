package com.ogu.emotion.application

import com.ogu.emotion.domain.EmotionAnalysisRepository
import com.ogu.shared.schedule.DueJob
import com.ogu.shared.schedule.DueTimers
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 시도할 차례인 분석을 다시 시도한다(research R2). 018 research R3부터는 주기마다 DB를 읽지 않고, 가장 이른
 * `next_attempt_at`에만 깨어난다. 할 일이 없으면 DB에 묻지 않으므로 Neon이 잠들 수 있다.
 *
 * - 돌고 나면 [nextDue]로 다음 시각을 다시 읽는다. 맡기(임대)와 실패(백오프)로 바뀐 시각은 이것으로 잡힌다.
 * - 글 작성 직후의 첫 시도는 [PostCreatedListener]가 하고, 끝나면 [rearm]을 부른다. 실패했다면 그 백오프 시각이 걸린다.
 * - 여러 인스턴스가 돌아도 `FOR UPDATE SKIP LOCKED`와 맡을 때 미뤄 두는 다음 시각 덕분에 같은 행을 두 번 맡지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.emotion.retry", name = ["scheduler-enabled"], matchIfMissing = true)
class RetryScheduler(
    private val runner: AnalysisRunner,
    private val repository: EmotionAnalysisRepository,
    // 밀린 재시도는 예전 폴링 간격마다 한 묶음씩 보낸다. AI 제공자의 요청 한도에 몰리지 않게 한다.
    @Value("\${ogu.emotion.retry.poll-interval:10s}") override val minGap: Duration,
    timers: DueTimers,
) : DueJob {
    private val log = LoggerFactory.getLogger(javaClass)
    private val timer = timers.register(this)

    override val name = "감정 분석 재시도"

    override fun nextDue(): Instant? = repository.findEarliestPendingAttemptAt()

    override fun runDue() {
        val handled = runner.runDue()
        if (handled > 0) log.info("감정 분석 재시도: {}건", handled)
    }

    /** 지금 걸린 다음 시각. 테스트와 로그용이다. */
    val nextAt: Instant?
        get() = timer.nextAt

    /** 다음 시각을 DB에서 다시 읽어 건다. 행을 만들거나 시각을 바꾼 쪽이 커밋 뒤에 부른다. */
    fun rearm() = timer.rearm()
}
