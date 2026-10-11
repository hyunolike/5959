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
    /**
     * 위험 분류에 쓰는 모델(005 SC-005). 감정 분석보다 큰 모델을 따로 쓴다. 죽음을 직접 말하지 않는 위기 표현을 작은
     * 모델은 절반쯤 놓쳤다(quickstart "AI까지 합친 성적"). 분류는 글 저장 뒤에 따로 돌아 느려도 글쓰기를 막지 않는다.
     */
    val riskModel: String = "nvidia/nemotron-3-super-120b-a12b",
    /** 위험 분류 응답의 토큰 상한. 답은 `{"level":"NONE"}` 한 줄이지만 추론 토큰이 함께 든다. */
    val riskMaxTokens: Int = 600,
    /** 위험 분류 모델에 보낼 추론 정도. 비우면 보내지 않는다. 기본 위험 분류 모델은 이 값을 받지 않는다. */
    val riskReasoningEffort: String = "",
    /** 위험 분류 호출의 타임아웃. 첫 재시도 간격(30초)보다 짧아야 한다. 맡은 호출이 끝나기 전에 다시 맡지 않게 한다. */
    val riskTimeout: Duration = Duration.ofSeconds(20),
    /** 주간 리포트 편지(008)의 토큰 상한. 답은 300자 이하지만 추론 토큰이 함께 든다. */
    val letterMaxTokens: Int = 900,
    /**
     * 모델이 추론에 들이는 정도(`low`, `medium`, `high`). 비우면 보내지 않는다. 분류는 짧은 판단이라 낮게 둬도 되고,
     * 낮을수록 빠르고 토큰이 덜 든다.
     */
    val reasoningEffort: String = "low",
    /** 임베딩 모델(007 research R2). 채팅 모델과 같은 공급자를 쓴다. 바꾸면 값을 차례로 다시 만든다. */
    val embeddingModel: String = "nvidia/nemotron-3-embed-1b",
    /** 임베딩의 차원. 저장하는 열(`post_embedding.embedding`)의 차원과 같아야 한다. */
    val embeddingDimensions: Int = 2048,
    val timeout: Duration = Duration.ofSeconds(20),
    val promptVersion: String = "v1",
)
