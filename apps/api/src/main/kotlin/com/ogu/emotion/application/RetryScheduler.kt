package com.ogu.emotion.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * `poll-interval`(10초)마다 시도할 차례인 분석을 다시 시도한다(research R2). 여러 인스턴스가 돌아도
 * `FOR UPDATE SKIP LOCKED`와 맡을 때 미뤄 두는 다음 시각 덕분에 같은 행을 두 번 맡지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.emotion.retry", name = ["scheduler-enabled"], matchIfMissing = true)
class RetryScheduler(
    private val runner: AnalysisRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("TooGenericExceptionCaught") // 한 번 실패해도 다음 주기에 다시 돈다
    @Scheduled(fixedDelayString = "\${ogu.emotion.retry.poll-interval}")
    fun poll() {
        try {
            val handled = runner.runDue()
            if (handled > 0) log.info("감정 분석 재시도: {}건", handled)
        } catch (e: RuntimeException) {
            log.error("감정 분석 재시도 주기가 실패했습니다", e)
        }
    }
}
