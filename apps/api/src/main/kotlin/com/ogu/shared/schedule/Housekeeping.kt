package com.ogu.shared.schedule

import com.ogu.shared.config.EventPublicationResubmitter
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 하루 한 번의 정리(018 research R3). 예전에는 정리 여섯 개가 04:00부터 04:50 사이에 따로 깼다. 사이가 5분보다 길어
 * DB가 그때마다 다시 깨므로, 04:00(한국 시간) 한 번에 묶어 차례로 돈다. 순서와 지우는 기준은 그대로다.
 *
 * - 단계마다 따로 실패한다. 한 단계가 실패해도 ERROR를 남기고 다음 단계를 돈다.
 * - 끝에 모든 [DueTimer]의 다음 시각을 DB에서 다시 읽고, 끝나지 않은 이벤트 발행을 한 번 다시 보낸다. 회원이 없는 동안
 *   멈춰 있던 재전송([ActivityGate])이 하루에 한 번은 돈다.
 */
@Component
class Housekeeping(
    steps: List<HousekeepingStep>,
    private val timers: DueTimers,
    private val resubmitter: EventPublicationResubmitter,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val steps = steps.sortedBy { it.order }

    @Scheduled(cron = "\${ogu.housekeeping.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    fun run() {
        steps.forEach { step -> attempt(step.stepName) { step.runStep() } }
        attempt("타이머 다시 걸기") { timers.rearmAll() }
        attempt("이벤트 재전송") { resubmitter.resubmit() }
    }

    /** 이름 목록. 테스트와 로그용이다. */
    val stepNames: List<String>
        get() = steps.map { it.stepName }

    @Suppress("TooGenericExceptionCaught") // 한 단계의 어떤 실패도 다음 단계를 막지 않는다
    private fun attempt(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: RuntimeException) {
            log.error("하루 정리의 {} 단계가 실패했습니다", name, e)
        }
    }
}
