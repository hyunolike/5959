package com.ogu.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 현재 시각은 이 [Clock]으로만 읽는다. 세션 만료처럼 시간에 따라 달라지는 동작을 테스트에서 고정된 시계로 검증하기 위해서다.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
