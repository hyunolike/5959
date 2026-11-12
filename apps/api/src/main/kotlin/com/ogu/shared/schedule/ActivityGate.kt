package com.ogu.shared.schedule

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 회원이 최근에 서비스를 썼는지 안다(018 research R3). 다음 시각을 알 수 없는 주기 작업(이벤트 재전송, 알림 안전망)은
 * 회원이 쓰는 동안만 돈다. 회원이 떠나면 그 작업들도 멈춰 DB가 잘 수 있다.
 *
 * "회원이 썼다"는 `/api/v1/` 아래 요청([ActivityFilter])과 커밋 뒤 이벤트 리스너([ActivityExecutorConfig])다. 헬스 체크와
 * 주기 작업 자신은 세지 않는다. 기동 직후 [window] 동안은 활성으로 본다(밀린 일을 따라잡는다).
 */
@Component
class ActivityGate(
    private val clock: Clock,
    @Value("\${ogu.activity.window:4m}") private val window: Duration,
) {
    @Volatile
    private var lastActivityAt: Instant = clock.instant()

    fun touch() {
        lastActivityAt = clock.instant()
    }

    fun isActive(): Boolean = Duration.between(lastActivityAt, clock.instant()) < window
}
