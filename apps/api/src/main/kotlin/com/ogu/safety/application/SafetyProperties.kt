package com.ogu.safety.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `ogu.safety` 설정(005 research R3, R4, R9, R13, R14). */
@ConfigurationProperties("ogu.safety")
data class SafetyProperties(
    val termRefreshInterval: Duration = Duration.ofSeconds(30),
    val retry: Retry = Retry(),
    val report: Report = Report(),
    val retention: Duration = Duration.ofDays(365),
    val backfill: Backfill = Backfill(),
) {
    data class Retry(
        val initial: Duration = Duration.ofSeconds(30),
        val maxInterval: Duration = Duration.ofMinutes(5),
        val deadline: Duration = Duration.ofHours(24),
        val pollInterval: Duration = Duration.ofSeconds(10),
        val batchSize: Int = 20,
    )

    data class Report(
        val maxPerHour: Int = 20,
    )

    data class Backfill(
        val enabled: Boolean = true,
        val batchSize: Int = 1000,
    )
}
