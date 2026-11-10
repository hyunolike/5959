package com.ogu.shared.schedule

import java.time.Duration
import java.time.Instant

/**
 * 다음에 할 일의 시각을 아는 주기 작업(018 research R3). [DueTimers]에 등록하면 그 시각에만 깨어 [runDue]를 부른다.
 *
 * 할 일이 없을 때 DB에 묻지 않으려는 것이다. 주기마다 DB를 읽으면 DB가 잠들지 못한다(Neon 무료는 5분 동안 쿼리가
 * 없어야 잔다).
 */
interface DueJob {
    /** 로그에 쓰는 이름. */
    val name: String

    /** 가장 이른 다음 시각. 할 일이 없으면 null. 지난 시각이면 바로 돈다. DB를 읽어도 된다. */
    fun nextDue(): Instant?

    /** 차례가 된 일을 한다. 끝나면 [nextDue]를 다시 읽어 다음 시각을 건다. */
    fun runDue()
}

/** [delay] 뒤에 [task]를 한 번 부른다. 테스트에서 시간을 직접 움직이려고 둔 틈이다. */
fun interface DelayScheduler {
    fun schedule(
        delay: Duration,
        task: Runnable,
    ): Cancellable
}

fun interface Cancellable {
    fun cancel()
}
