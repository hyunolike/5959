package com.ogu.report.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.LocalTime

/** `ogu.report` 설정(008 data-model.md "설정"). */
@ConfigurationProperties("ogu.report")
data class ReportProperties(
    /** 월요일 이 시각(한국 시간)부터 지난주 리포트를 만든다. 0시 직전에 쓴 글의 감정 분석이 끝날 시간을 준다. */
    val publishAt: LocalTime = LocalTime.of(5, 0),
    /** 대상 회원을 한 번에 읽는 수. */
    val batchSize: Int = 200,
    /** 한 차례에 만드는 리포트 수의 한도. 주기 스레드를 오래 잡지 않고 AI 호출이 한꺼번에 몰리지 않게 한다. */
    val maxPerTick: Int = 500,
    val letter: Letter = Letter(),
    val retention: Duration = Duration.ofDays(365),
) {
    data class Letter(
        val initialInterval: Duration = Duration.ofSeconds(30),
        val maxInterval: Duration = Duration.ofMinutes(5),
        val deadline: Duration = Duration.ofHours(24),
        val batchSize: Int = 20,
        /** 편지의 길이 상한(그래핌). */
        val maxLength: Int = 300,
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
@EnableConfigurationProperties(ReportProperties::class)
class ReportConfig
