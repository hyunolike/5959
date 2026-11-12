package com.ogu.member.application

import com.ogu.member.domain.LoginAttemptRepository
import com.ogu.shared.schedule.HousekeepingStep
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * 하루 넘게 쓰이지 않은 로그인 실패 기록을 매일 지운다(data-model.md `login_attempt`). 가장 긴 창과 차단이 각 1시간이라
 * 하루 지난 행은 차단 판단에 더는 쓰이지 않는다.
 */
@Component
class LoginAttemptCleanupJob(
    private val repository: LoginAttemptRepository,
    private val clock: Clock,
) : HousekeepingStep {
    override val stepName = "로그인 실패 기록 정리"
    override val order = 20

    /** 하루 정리([com.ogu.shared.schedule.Housekeeping])가 04:00(한국 시간)에 부른다. */
    override fun runStep() {
        deleteStaleAttempts()
    }

    private val log = LoggerFactory.getLogger(javaClass)

    fun deleteStaleAttempts(): Int {
        val deleted = repository.deleteUntouchedBefore(clock.instant().minus(RETENTION))
        log.info("Deleted {} stale login_attempt rows", deleted)
        return deleted
    }

    companion object {
        private val RETENTION: Duration = Duration.ofDays(1)
    }
}
