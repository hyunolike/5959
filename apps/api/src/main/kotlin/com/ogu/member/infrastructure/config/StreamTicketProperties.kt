package com.ogu.member.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `ogu.stream-ticket` 설정(004 research R3). */
@ConfigurationProperties("ogu.stream-ticket")
data class StreamTicketProperties(
    val ttl: Duration = Duration.ofSeconds(30),
)
