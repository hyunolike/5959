package com.ogu.recommend

/** 다른 모듈이 추천을 읽는 파사드(007 data-model.md). 글의 ID만 준다. 카드로 조립하는 일은 feed가 한다. */
interface RecommendApi {
    /**
     * [postId]와 비슷한 글을 [limit]개까지. [viewerId]가 쓴 글, 다른 회원에게 숨긴 글, 지운 글, [postId] 자신은 없다.
     * [postId]가 [viewerId]에게 보이는 글인지는 부르는 쪽이 먼저 확인한다.
     */
    fun similar(
        postId: Long,
        viewerId: Long,
        limit: Int,
    ): Recommendation
}

/** 어느 쪽으로 찾았는가. */
enum class RecommendationBasis {
    /** 뜻이 가까운 글(임베딩). */
    SIMILAR,

    /** 같은 감정의 최근 글. 임베딩이 없거나 가까운 글이 없을 때 대신한다. */
    SAME_EMOTION,

    /** 보여 줄 글이 없다. */
    NONE,
}

/**
 * 추천 결과. [postIds]는 [basis]가 [RecommendationBasis.SIMILAR]면 가까운 순서, [RecommendationBasis.SAME_EMOTION]이면
 * 최근 순서다. [pending]이면 이 글의 임베딩을 아직 만들고 있어 잠시 뒤 결과가 달라질 수 있다.
 */
data class Recommendation(
    val postIds: List<Long>,
    val basis: RecommendationBasis,
    val pending: Boolean,
)
