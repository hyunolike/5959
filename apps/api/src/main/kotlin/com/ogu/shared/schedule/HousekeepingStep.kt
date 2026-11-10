package com.ogu.shared.schedule

/**
 * 매일 정리 한 단계(018 research R3). 각 모듈이 자기 정리 잡을 이 빈으로 내놓으면 [Housekeeping]이 하루 한 번
 * [order] 순서대로 차례로 부른다. `shared`가 다른 모듈을 직접 부르지 않게 하려는 틈이다(모듈 경계).
 */
interface HousekeepingStep {
    /** 로그에 쓰는 이름. */
    val stepName: String

    /** 작을수록 먼저 돈다. 예전 cron 시각의 순서를 지킨다. */
    val order: Int

    fun runStep()
}
