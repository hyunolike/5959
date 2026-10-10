package com.ogu.recommend.application

import com.ogu.ai.Embedder
import com.ogu.emotion.EmotionApi
import com.ogu.post.PostSelectionApi
import com.ogu.recommend.RecommendApi
import com.ogu.recommend.Recommendation
import com.ogu.recommend.RecommendationBasis
import com.ogu.recommend.domain.PostEmbeddingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 비슷한 글 찾기(007 research R5, R6). 읽을 때는 공급자를 부르지 않는다. 저장해 둔 값끼리 견준다.
 *
 * 1. 글의 값이 지금 모델로 있으면 코사인 거리가 가까운 순서로 후보를 읽고, 기준 안인 것 가운데 보이는 글을 준다.
 * 2. 값이 없거나 기준 안인 보이는 글이 없으면 같은 감정의 최근 글로 대신한다.
 * 3. 감정 분석 결과도 없거나 대신할 글도 없으면 아무것도 주지 않는다.
 *
 * 숨겼거나 지운 글, 보는 사람 자신의 글은 어느 길에서도 나오지 않는다. 보이는지는 post 모듈이 정한다.
 */
@Service
class SimilarPostsService(
    private val embeddings: PostEmbeddingRepository,
    private val embedder: Embedder,
    private val emotionApi: EmotionApi,
    private val posts: PostSelectionApi,
    private val properties: RecommendProperties,
) : RecommendApi {
    @Transactional(readOnly = true)
    override fun similar(
        postId: Long,
        viewerId: Long,
        limit: Int,
    ): Recommendation {
        val state = embeddings.stateOf(postId, embedder.model)
        // 행이 아직 없으면 방금 쓴 글이거나 이미 있는 글을 처리하기 전이다. 곧 만들어진다
        val pending = state == null || state.status == PENDING
        val near = if (state?.usable == true) nearest(postId, viewerId, limit) else emptyList()
        if (near.isNotEmpty()) return Recommendation(near, RecommendationBasis.SIMILAR, pending = false)

        val sameEmotion = sameEmotion(postId, viewerId, limit)
        val basis = if (sameEmotion.isEmpty()) RecommendationBasis.NONE else RecommendationBasis.SAME_EMOTION
        return Recommendation(sameEmotion, basis, pending)
    }

    private fun nearest(
        postId: Long,
        viewerId: Long,
        limit: Int,
    ): List<Long> {
        val candidates =
            embeddings
                .nearest(postId, viewerId, embedder.model, properties.candidates)
                .filter { it.distance <= properties.maxDistance }
                .map { it.postId }
        val visible = posts.visibleIds(candidates, exceptAuthorId = viewerId)
        return candidates.filter { it in visible }.take(limit)
    }

    private fun sameEmotion(
        postId: Long,
        viewerId: Long,
        limit: Int,
    ): List<Long> {
        val emotion = emotionApi.findByPostIds(listOf(postId))[postId]?.emotion ?: return emptyList()
        val candidates = emotionApi.recentPostIds(emotion, properties.candidates).filter { it != postId }
        val visible = posts.visibleIds(candidates, exceptAuthorId = viewerId)
        return candidates.filter { it in visible }.take(limit)
    }

    private companion object {
        const val PENDING = "PENDING"
    }
}
