package com.ogu.ai

/**
 * 모델이 돌려준 분류. ai 모듈은 emotion 모듈 타입을 쓰지 않으므로 자체 enum을 쓰고, emotion 모듈이 변환한다.
 * [reason]은 로그용 분류 근거다.
 */
data class EmotionClassification(
    val emotion: ClassifiedEmotion,
    val intensity: ClassifiedIntensity,
    val reason: String,
)

enum class ClassifiedEmotion {
    ANXIETY,
    LETHARGY,
    LONELINESS,
    SELF_DEPRECATION,
    IRRITATION,
}

enum class ClassifiedIntensity {
    LOW,
    MEDIUM,
    HIGH,
}
