package com.ogu.emotion

/** 감정 분석 상태. `DEFAULTED`는 24시간 안에 분석하지 못해 기본값(LETHARGY, LOW)을 쓴 경우다. */
enum class AnalysisStatus {
    PENDING,
    ANALYZED,
    DEFAULTED,
}
