package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedRisk
import com.ogu.ai.EmotionAnalysisFailed.Kind.INVALID_RESPONSE
import com.ogu.ai.RiskClassificationFailed
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/**
 * 모델 응답을 `{"level": "NONE|CONCERN|CRISIS"}`로 엄격하게 읽는다(005 research R3). 목록에 없는 값, 빠진 필드, JSON이
 * 아닌 응답은 모두 `INVALID_RESPONSE`다. 모델이 JSON을 코드 블록으로 감싼 경우만 벗겨 낸다. 근거 문장은 받지 않는다
 * (저장할 민감 정보를 늘리지 않는다). 응답 원문은 로그나 예외 메시지에 넣지 않는다.
 */
object RiskResponseParser {
    private val jsonMapper = JsonMapper.builder().build()
    private val levels = ClassifiedRisk.entries.associateBy { it.name }
    private val codeFence = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$")

    fun parse(raw: String?): ClassifiedRisk {
        val text = raw?.trim() ?: invalid()
        val body = codeFence.matchEntire(text)?.groupValues?.get(1) ?: text
        val node =
            try {
                jsonMapper.readTree(body)
            } catch (_: JacksonException) {
                invalid()
            }
        if (node == null || !node.isObject) invalid()
        val level = node.get("level")?.takeIf { it.isString }?.asString()
        return levels[level] ?: invalid()
    }

    private fun invalid(): Nothing = throw RiskClassificationFailed(INVALID_RESPONSE)
}
