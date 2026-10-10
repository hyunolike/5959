package com.ogu.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * `ogu.ai` 설정. 003-core-loop의 감정 분석(Batch 4)이 이 값으로 OpenAI 호환 클라이언트를 직접 만들어 쓴다.
 * spring-ai-starter-model-openai의 spring.ai.openai.* 자동설정은 application.yml에서 꺼 두었다(T003).
 */
@ConfigurationProperties("ogu.ai")
data class AiProperties(
    val baseUrl: URI = URI.create("https://integrate.api.nvidia.com/v1"),
    /** 비어 있으면 기동하지 않는다(prod, [com.ogu.shared.config.ProdAiSettingsCheck]). */
    val apiKey: String = "",
    val model: String = "openai/gpt-oss-20b",
    val temperature: Double = 0.1,
    /**
     * 감정 분석 응답의 토큰 상한. 기본 모델은 답보다 추론 과정을 먼저 내고 그 토큰도 상한에 들어간다. 상한이 작으면
     * 추론만 하다 끝나 답이 비어 온다.
     */
    val maxTokens: Int = 600,
    /** 위험 분류 응답의 토큰 상한. 답은 `{"level":"NONE"}` 한 줄이지만 추론 토큰이 함께 든다. */
    val riskMaxTokens: Int = 300,
    /**
     * 모델이 추론에 들이는 정도(`low`, `medium`, `high`). 비우면 보내지 않는다. 분류는 짧은 판단이라 낮게 둬도 되고,
     * 낮을수록 빠르고 토큰이 덜 든다.
     */
    val reasoningEffort: String = "low",
    val timeout: Duration = Duration.ofSeconds(20),
    val promptVersion: String = "v1",
)
