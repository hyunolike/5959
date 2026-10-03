package com.ogu.emotion

/** 다른 모듈에 보여 주는 분석 상태. `PENDING`이면 [emotion]과 [intensity]가 null이다. */
data class EmotionView(
    val status: AnalysisStatus,
    val emotion: EmotionType?,
    val intensity: Intensity?,
)
