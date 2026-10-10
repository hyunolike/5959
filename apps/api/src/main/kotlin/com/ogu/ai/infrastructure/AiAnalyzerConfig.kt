package com.ogu.ai.infrastructure

import com.ogu.ai.Embedder
import com.ogu.ai.EmotionAnalyzer
import com.ogu.ai.RiskClassifier
import com.ogu.ai.WeeklyLetterWriter
import com.ogu.shared.config.AiProperties
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * 실제 감정 분석기. `e2e` 프로필이 아니면 쓴다. 키가 비어 있으면 외부로 호출하지 않는 [DisabledEmotionAnalyzer]를 쓴다. 서킷 브레이커와 타임아웃 설정은 application.yml의
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
            // 키 없이 실제 클라이언트를 만들면 글 본문이 외부 기준 URL로 나간다. 호출하지 않는 분석기로 대신한다.
            LoggerFactory
                .getLogger(javaClass)
                .warn("ogu.ai.api-key(AI_API_KEY)가 비어 있어 감정 분석을 끕니다. 모든 시도가 실패하고 24시간 뒤 기본 몬스터가 됩니다.")
            return DisabledEmotionAnalyzer()
        }
        return SpringAiEmotionAnalyzer.create(
            properties,
            circuitBreakerRegistry.circuitBreaker(SpringAiEmotionAnalyzer.RESILIENCE_NAME),
            timeLimiterRegistry.timeLimiter(SpringAiEmotionAnalyzer.RESILIENCE_NAME),
        )
    }

    /**
     * 실제 위험 분류기(005 research R3). 키가 비어 있으면 외부로 호출하지 않고 바로 실패하는 분류기를 쓴다. 그때는
     * 키워드 규칙의 판정이 최종이 된다.
     */
    @Bean
    fun springAiRiskClassifier(
        properties: AiProperties,
        circuitBreakerRegistry: CircuitBreakerRegistry,
        timeLimiterRegistry: TimeLimiterRegistry,
    ): RiskClassifier {
        if (properties.apiKey.isBlank()) {
            LoggerFactory
                .getLogger(javaClass)
                .warn("ogu.ai.api-key(AI_API_KEY)가 비어 있어 AI 위험 분류를 끕니다. 키워드 규칙만으로 판정합니다.")
            return FakeRiskClassifier.Disabled()
        }
        return SpringAiRiskClassifier.create(
            properties,
            circuitBreakerRegistry.circuitBreaker(SpringAiRiskClassifier.RESILIENCE_NAME),
            timeLimiterRegistry.timeLimiter(SpringAiRiskClassifier.RESILIENCE_NAME),
        )
    }
}

/**
 * 실제 임베더(007 research R2). `e2e` 프로필이 아니면 쓴다. 키가 비어 있으면 외부로 보내지 않고 바로 실패한다.
 * 그때 추천은 같은 감정의 글로 대신한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!e2e")
class EmbedderConfig {
    @Bean
    fun httpEmbedder(
        properties: AiProperties,
        circuitBreakerRegistry: CircuitBreakerRegistry,
    ): Embedder {
        if (properties.apiKey.isBlank()) {
            LoggerFactory
                .getLogger(javaClass)
                .warn("ogu.ai.api-key(AI_API_KEY)가 비어 있어 임베딩을 끕니다. 추천은 같은 감정의 글로 대신합니다.")
            return FakeEmbedder.Disabled()
        }
        return HttpEmbedder(properties, circuitBreakerRegistry.circuitBreaker(HttpEmbedder.RESILIENCE_NAME))
    }
}

/**
 * 실제 편지 쓰기(008 research R7). `e2e` 프로필이 아니면 쓴다. 키가 비어 있으면 외부로 보내지 않고 바로 실패한다.
 * 그때 리포트는 수치만으로 나가고 편지 없이 닫힌다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!e2e")
class WeeklyLetterWriterConfig {
    @Bean
    fun springAiWeeklyLetterWriter(
        properties: AiProperties,
        circuitBreakerRegistry: CircuitBreakerRegistry,
        timeLimiterRegistry: TimeLimiterRegistry,
        @Value("\${ogu.report.letter.max-length:300}") maxLength: Int,
    ): WeeklyLetterWriter {
        if (properties.apiKey.isBlank()) {
            LoggerFactory
                .getLogger(javaClass)
                .warn("ogu.ai.api-key(AI_API_KEY)가 비어 있어 주간 리포트의 편지를 끕니다. 리포트는 수치만으로 나갑니다.")
            return FakeWeeklyLetterWriter.Disabled()
        }
        return SpringAiWeeklyLetterWriter.create(
            properties,
            circuitBreakerRegistry.circuitBreaker(SpringAiWeeklyLetterWriter.RESILIENCE_NAME),
            timeLimiterRegistry.timeLimiter(SpringAiWeeklyLetterWriter.RESILIENCE_NAME),
            maxLength,
        )
    }
}

/** e2e 프로필은 결정적인 가짜 분석기를 쓴다(research R3). 테스트는 테스트 설정에서 따로 등록한다. */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
class FakeEmotionAnalyzerConfig {
    @Bean
    fun fakeEmotionAnalyzer(): EmotionAnalyzer = FakeEmotionAnalyzer()

    @Bean
    fun fakeRiskClassifier(): RiskClassifier = FakeRiskClassifier()

    @Bean
    fun fakeEmbedder(): Embedder = FakeEmbedder()

    @Bean
    fun fakeWeeklyLetterWriter(): WeeklyLetterWriter = FakeWeeklyLetterWriter()
}
