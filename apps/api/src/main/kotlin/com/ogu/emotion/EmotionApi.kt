package com.ogu.emotion

/** emotion 모듈 파사드. 다른 모듈은 emotion_analysis 테이블을 직접 읽지 않는다. */
interface EmotionApi {
    /** 글 ID별 분석 상태. 분석 기록이 없는 글은 결과에 없다. */
    fun findByPostIds(postIds: Collection<Long>): Map<Long, EmotionView>
}
