package com.ogu.emotion

/** emotion 모듈 파사드. 다른 모듈은 emotion_analysis 테이블을 직접 읽지 않는다. */
interface EmotionApi {
    /** 글 ID별 분석 상태. 분석 기록이 없는 글은 결과에 없다. */
    fun findByPostIds(postIds: Collection<Long>): Map<Long, EmotionView>

    /**
     * 감정이 [emotion]으로 정해진 글의 ID를 최근 글부터 [limit]개. 추천이 임베딩 없이 같은 감정의 글로 대신할 때 쓴다
     * (007 research R6). 글이 보이는지는 부르는 쪽이 post 모듈로 확인한다.
     */
    fun recentPostIds(
        emotion: EmotionType,
        limit: Int,
    ): List<Long>
}
