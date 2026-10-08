package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.ClassifiedIntensity
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.EmotionClassification
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 모델 응답을 `{"emotion": ..., "intensity": ..., "reason": ...}`로 엄격하게 읽는다(research R3).
 * 목록에 없는 값, 빠진 필드, JSON이 아닌 응답은 모두 [EmotionAnalysisFailed.Kind.INVALID_RESPONSE]다.
 * 모델이 지시를 어기고 JSON을 코드 블록(```)으로 감싸는 경우만 벗겨 낸다.
 *
 * 응답 원문은 로그나 예외 메시지에 넣지 않는다. Jackson 예외 메시지에는 원문 일부가 들어가므로 원인으로도 붙이지 않는다.
 */
object EmotionResponseParser {
    private val jsonMapper = JsonMapper.builder().build()
    private val emotions = ClassifiedEmotion.entries.associateBy { it.name }
    private val intensities = ClassifiedIntensity.entries.associateBy { it.name }

    fun parse(raw: String?): EmotionClassification {
        val node = readObject(stripCodeFence(raw ?: invalid()))
        val emotion = emotions[node.textField("emotion")] ?: invalid()
        val intensity = intensities[node.textField("intensity")] ?: invalid()
        val reason = node.textField("reason")
        return EmotionClassification(emotion, intensity, reason.trim())
    }

    private fun readObject(text: String): JsonNode {
        val node =
            try {
                jsonMapper.readTree(text)
            } catch (_: JacksonException) {
                invalid()
            }
        return if (node != null && node.isObject) node else invalid()
    }

    private fun JsonNode.textField(name: String): String {
        val value = get(name)
        return if (value != null && value.isString) value.asString() else invalid()
    }

    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        val match = CODE_FENCE.matchEntire(trimmed) ?: return trimmed
        return match.groupValues[1].trim()
    }

    private fun invalid(): Nothing = throw EmotionAnalysisFailed(EmotionAnalysisFailed.Kind.INVALID_RESPONSE)

    private val CODE_FENCE = Regex("^```(?:json)?\\s*(.*?)\\s*```$", RegexOption.DOT_MATCHES_ALL)
}
