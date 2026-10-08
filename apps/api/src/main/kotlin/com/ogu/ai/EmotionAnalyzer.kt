package com.ogu.ai

/**
 * 본문의 감정을 분류한다. 실패하면 [EmotionAnalysisFailed]를 던진다. 재시도는 호출하는 쪽(emotion 모듈)이 맡는다.
 */
interface EmotionAnalyzer {
    fun analyze(
        postId: Long,
        content: String,
    ): EmotionClassification
}
