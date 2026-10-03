package com.ogu.emotion.application

import com.ogu.emotion.domain.Backoff
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * `ogu.emotion.retry` 설정(FR-004, research R2). [schedulerEnabled]는 실행기를 직접 돌리는 테스트에서만 끈다.
 */
@ConfigurationProperties("ogu.emotion.retry")
data class EmotionRetryProperties(
    val initial: Duration = Duration.ofSeconds(30),
    val maxInterval: Duration = Duration.ofMinutes(5),
    val deadline: Duration = Duration.ofHours(24),
    val pollInterval: Duration = Duration.ofSeconds(10),
    val batchSize: Int = 20,
    val schedulerEnabled: Boolean = true,
) {
    val backoff: Backoff
        get() = Backoff(initial, maxInterval)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmotionRetryProperties::class)
class EmotionConfig
