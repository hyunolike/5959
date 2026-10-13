package com.ogu.companion.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.random.RandomGenerator

/** `ogu.companion` 설정(009 data-model.md "설정"). */
@ConfigurationProperties("ogu.companion")
data class CompanionProperties(
    /** 꺼져 있으면 주기 작업이 아무 글도 맡지 않는다. 반응 기록은 꺼져 있어도 생긴다(research R4, R12). */
    val enabled: Boolean = false,
    /** 글이 올라온 뒤 다른 회원의 댓글을 기다리는 시간. */
    val wait: Duration = Duration.ofMinutes(10),
    /** 이보다 오래된 글에는 반응하지 않는다. */
    val maxAge: Duration = Duration.ofHours(24),
    /** 하루(한국 시간)에 AI에 댓글을 청하는 횟수(research R8). 실패한 시도도 든다. */
    val dailyLimit: Int = 45,
    /** 한 글에 댓글을 청하는 횟수. 한 글이 하루의 몫을 다 쓰지 못하게 한다. */
    val maxAttempts: Int = 3,
    /** 한 차례에 맡는 글 수. `@Scheduled` 스레드가 둘뿐이라 작게 둔다. */
    val batchSize: Int = 3,
    val retry: Retry = Retry(),
    /** 글의 위험 판정이 끝나지 않았을 때 다시 보는 간격. */
    val riskWait: Duration = Duration.ofSeconds(30),
    /** 한 글에 반응할 AI 친구의 수를 뽑는 무게. 차례대로 1명, 2명, 3명이다(012 research R2). */
    val countWeights: List<Int> = listOf(60, 30, 10),
    val followUp: FollowUp = FollowUp(),
) {
    /** 앞 친구의 댓글이 올라간 뒤 다음 친구가 반응하기까지의 간격(012 research R3). */
    data class FollowUp(
        val minGap: Duration = Duration.ofMinutes(3),
        val maxGap: Duration = Duration.ofMinutes(10),
    ) {
        /** [minGap]과 [maxGap] 사이에서 무작위로 고른 간격. 가장 긴 것이 더 짧게 적혀 있으면 가장 짧은 것으로 맞춘다. */
        fun gap(random: RandomGenerator): Duration {
            val min = maxOf(minGap, Duration.ZERO)
            val spread = maxGap.minus(min).toMillis()
            return if (spread <= 0) min else min.plusMillis(random.nextLong(spread + 1))
        }
    }

    data class Retry(
        val initialInterval: Duration = Duration.ofMinutes(1),
        val maxInterval: Duration = Duration.ofMinutes(5),
    ) {
        /** [attempts]번 시도한 뒤 다음 시도까지 기다릴 시간. 두 배씩 늘리다 상한에서 멈춘다. */
        fun delayAfter(attempts: Int): Duration {
            var delay = initialInterval
            repeat((attempts - 1).coerceAtLeast(0)) {
                delay = delay.multipliedBy(2)
                if (delay >= maxInterval) return maxInterval
            }
            return minOf(delay, maxInterval)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CompanionProperties::class)
class CompanionConfig
