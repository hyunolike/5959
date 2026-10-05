package com.ogu.notification.application

import com.ogu.notification.domain.NotificationRetention
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/** `ogu.notification` 설정(research R2~R5, R10). 값은 application.yml에 있다. */
@ConfigurationProperties("ogu.notification")
data class NotificationProperties(
    val heartbeat: Duration = Duration.ofSeconds(25),
    val connectionLifetime: Duration = Duration.ofMinutes(15),
    val maxConnectionsPerMember: Int = 5,
    val retention: Duration = Duration.ofDays(90),
    val safetyDrainInterval: Duration = Duration.ofSeconds(60),
    val outageDrainInterval: Duration = Duration.ofSeconds(5),
    val purgeCron: String = "0 0 4 * * *",
    val purgeBatchSize: Int = 1000,
    val writerThreads: Int = 4,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties::class)
class NotificationConfig {
    /** 보관 기간 조건은 주입한 [Clock]과 `ogu.notification.retention`으로만 만든다. */
    @Bean
    fun notificationRetention(
        clock: Clock,
        properties: NotificationProperties,
    ): NotificationRetention = NotificationRetention(clock, properties.retention)
}
