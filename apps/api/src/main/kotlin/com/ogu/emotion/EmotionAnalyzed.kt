package com.ogu.emotion

/** 분석이 끝났다(성공 또는 기본값). 커밋 뒤 비동기로 monster 모듈이 몬스터를 만든다. */
data class EmotionAnalyzed(
    val postId: Long,
    val emotion: EmotionType,
    val intensity: Intensity,
    val defaulted: Boolean,
)
