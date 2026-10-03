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
    val model: String = "qwen/qwen3-next-80b-a3b-instruct",
    val temperature: Double = 0.1,
    val maxTokens: Int = 200,
    val timeout: Duration = Duration.ofSeconds(20),
    val promptVersion: String = "v1",
)
