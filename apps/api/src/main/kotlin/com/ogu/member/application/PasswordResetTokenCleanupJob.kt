package com.ogu.member.application

import com.ogu.member.domain.PasswordResetTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * 만료된 지 하루가 지난 재설정 링크를 매일 지운다(015 data-model). 링크는 30분이면 만료되므로 하루 지난 행은 어떤 판단에도
 * 쓰이지 않는다. 인스턴스가 둘이어도 지우기는 멱등이다.
 */
@Component
class PasswordResetTokenCleanupJob(
    private val repository: PasswordResetTokenRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${ogu.password-reset.cleanup-cron:0 50 4 * * *}", zone = "Asia/Seoul")
    fun deleteStaleTokens(): Int {
        val deleted = repository.deleteExpiredBefore(clock.instant().minus(RETENTION))
        log.info("Deleted {} stale password_reset_token rows", deleted)
        return deleted
    }

    companion object {
        private val RETENTION: Duration = Duration.ofDays(1)
    }
}
