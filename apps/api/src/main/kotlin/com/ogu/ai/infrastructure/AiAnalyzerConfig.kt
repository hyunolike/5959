package com.ogu.ai.infrastructure

import com.ogu.ai.EmotionAnalyzer
import com.ogu.shared.config.AiProperties
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * 실제 감정 분석기. `e2e` 프로필이 아니면 쓴다. 서킷 브레이커와 타임아웃 설정은 application.yml의
 * `resilience4j.circuitbreaker.instances.emotionAnalyzer`, `resilience4j.timelimiter.instances.emotionAnalyzer`에 있다.
 * Spring AI의 OpenAI 자동설정은 꺼 두었으므로(application.yml) ChatModel은 빈으로 노출하지 않고 여기서 만든다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!e2e")
class SpringAiAnalyzerConfig {
    @Bean
    fun springAiEmotionAnalyzer(
        properties: AiProperties,
        circuitBreakerRegistry: CircuitBreakerRegistry,
        timeLimiterRegistry: TimeLimiterRegistry,
    ): EmotionAnalyzer {
        if (properties.apiKey.isBlank()) {
            // prod는 ProdAiSettingsCheck가 막는다. 로컬에서는 호출이 실패해 재시도 끝에 기본 몬스터가 된다.
            LoggerFactory.getLogger(javaClass).warn("ogu.ai.api-key(AI_API_KEY)가 비어 있어 감정 분석 호출이 실패합니다.")
        }
        return SpringAiEmotionAnalyzer.create(
            properties,
            circuitBreakerRegistry.circuitBreaker(SpringAiEmotionAnalyzer.RESILIENCE_NAME),
            timeLimiterRegistry.timeLimiter(SpringAiEmotionAnalyzer.RESILIENCE_NAME),
        )
    }
}

/** e2e 프로필은 결정적인 가짜 분석기를 쓴다(research R3). 테스트는 테스트 설정에서 따로 등록한다. */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
class FakeEmotionAnalyzerConfig {
    @Bean
    fun fakeEmotionAnalyzer(): EmotionAnalyzer = FakeEmotionAnalyzer()
}
