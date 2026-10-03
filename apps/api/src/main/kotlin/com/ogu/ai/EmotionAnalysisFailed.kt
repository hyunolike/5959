package com.ogu.ai

/**
 * 감정 분류 실패. [kind]는 `emotion_analysis.last_error`에 남길 분류이며, 모델 응답 원문은 담지 않는다.
 */
class EmotionAnalysisFailed(
    val kind: Kind,
    cause: Throwable? = null,
) : RuntimeException("감정 분석 실패: $kind", cause) {
    enum class Kind {
        TIMEOUT,
        INVALID_RESPONSE,
        CIRCUIT_OPEN,
        UPSTREAM_ERROR,
    }
}
