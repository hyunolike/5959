package com.ogu.recommend.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/** `ogu.recommend` 설정(007 data-model.md "설정"). */
@ConfigurationProperties("ogu.recommend")
data class RecommendProperties(
    /** 코사인 거리가 이보다 가까운 글만 비슷한 고민으로 보인다(research R5, R9). */
    val maxDistance: Double = 0.40,
    /** 보이는 글만 거르기 전에 읽는 후보 수. 숨긴 글과 지운 글이 섞여 있어도 다섯 개를 채울 만큼 넉넉히 읽는다. */
    val candidates: Int = 30,
    val retry: Retry = Retry(),
    val backfill: Backfill = Backfill(),
) {
    data class Retry(
        val initial: Duration = Duration.ofSeconds(30),
        val maxInterval: Duration = Duration.ofMinutes(5),
        val deadline: Duration = Duration.ofHours(24),
        val pollInterval: Duration = Duration.ofSeconds(10),
        val batchSize: Int = 20,
    ) {
        /** [attempts]번 시도한 뒤 다음 시도까지 기다릴 시간. 두 배씩 늘리다 상한에서 멈춘다(30s, 60s, 120s, 240s, 300s…). */
        fun delayAfter(attempts: Int): Duration {
            var delay = initial
            repeat((attempts - 1).coerceAtLeast(0)) {
                delay = delay.multipliedBy(2)
                if (delay >= maxInterval) return maxInterval
            }
            return minOf(delay, maxInterval)
        }
    }

    data class Backfill(
        val enabled: Boolean = true,
        val batchSize: Int = 1000,
    )
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecommendProperties::class)
class RecommendConfig
