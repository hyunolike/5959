package com.ogu.ai

/**
 * 본문의 위험 신호를 분류한다(005 research R3). 실패하면 [RiskClassificationFailed]를 던진다. 재시도는 호출하는 쪽
 * (safety 모듈)이 맡는다. [key]는 로그에 남길 대상 식별자다(예: `POST:12`). 본문은 로그에 남기지 않는다.
 */
interface RiskClassifier {
    fun classify(
        key: String,
        content: String,
    ): ClassifiedRisk
}

/** 모델이 돌려준 위험 단계. ai 모듈은 safety 모듈 타입을 쓰지 않으므로 자체 enum을 쓰고, safety 모듈이 옮긴다. */
enum class ClassifiedRisk {
    NONE,
    CONCERN,
    CRISIS,
}

/** 위험 분류 실패. [kind]는 `risk_assessment.last_error`에 남길 분류이며, 모델 응답 원문은 담지 않는다. */
class RiskClassificationFailed(
    val kind: EmotionAnalysisFailed.Kind,
) : RuntimeException("위험 분류 실패: $kind")
