package com.ogu.raid.application

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.Instant
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
    /** 기동 뒤 기록과 맞추는 기간과 간격(018 research R6). 배포가 겹친 옛 인스턴스의 마지막 기록을 받아들인다. */
    val reconcileWindow: Duration = Duration.ofMinutes(3),
    val reconcileInterval: Duration = Duration.ofSeconds(30),
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    /** 보스가 끝난 다음 날 0시([zone]). 23시 59분에 끝나도 다음 날 0시다(US5-AC3). */
    fun nextBossAt(endedAt: Instant): Instant =
        endedAt
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()

    data class Hp(
        val perParticipant: Int = 100,
        val min: Int = 300,
        val max: Int = 5000,
    )
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RaidProperties::class)
class RaidConfig
