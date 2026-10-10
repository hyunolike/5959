package com.ogu.raid.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.ZoneId

/** `ogu.raid` 설정(006 data-model.md "설정"). 값을 바꾸면 다음에 나오는 보스부터 적용된다. */
@ConfigurationProperties("ogu.raid")
data class RaidProperties(
    val cooldown: Duration = Duration.ofSeconds(1),
    val hp: Hp = Hp(),
    val emotionWindow: Duration = Duration.ofDays(7),
    val retreatAfter: Duration = Duration.ofDays(7),
    val flushInterval: Duration = Duration.ofSeconds(1),
    val broadcastInterval: Duration = Duration.ofMillis(250),
    val lifecycleInterval: Duration = Duration.ofMinutes(1),
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    data class Hp(
        val perParticipant: Int = 100,
        val min: Int = 300,
        val max: Int = 5000,
    )
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RaidProperties::class)
class RaidConfig
