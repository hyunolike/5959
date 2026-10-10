package com.ogu.ai.infrastructure

import org.springframework.ai.openai.OpenAiChatOptions

/** `ogu.ai.reasoning-effort`가 비어 있으면 보내지 않는다. 이 값을 모르는 모델과 공급자도 있다. */
internal fun OpenAiChatOptions.Builder.withReasoningEffort(effort: String): OpenAiChatOptions.Builder =
    if (effort.isBlank()) this else reasoningEffort(effort)
