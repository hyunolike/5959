package com.ogu.feed.application

import com.ogu.feed.presentation.dto.FeedItemResponse
import com.ogu.post.PostApi
import com.ogu.post.PostSelectionApi
import com.ogu.recommend.RecommendApi
import com.ogu.recommend.RecommendationBasis
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Service

/** 계약의 `SimilarPosts`. [items]는 피드와 같은 항목이다. */
data class SimilarPostsResponse(
    val basis: RecommendationBasis,
    val items: List<FeedItemResponse>,
    val pending: Boolean,
)

/**
 * 글 상세 아래의 비슷한 고민(007 US1, US2). 추천 모듈이 고른 글 ID를 피드와 같은 [FeedAssembler]로 조립한다. 그래서 숨김,
 * 욕설 가리기, 카드의 모양이 피드와 똑같다. 지금 글이 보는 사람에게 보이지 않으면 글 상세와 같이 404다.
 */
@Service
class SimilarPostsQuery(
    private val postApi: PostApi,
    private val recommendApi: RecommendApi,
    private val posts: PostSelectionApi,
    private val feedAssembler: FeedAssembler,
) {
    fun get(
        postId: Long,
        viewerId: Long,
    ): SimilarPostsResponse {
        postApi.findForViewer(postId, viewerId) ?: throw BusinessException(ErrorCode.POST_NOT_FOUND)
        val recommendation = recommendApi.similar(postId, viewerId, LIMIT)
        val items = feedAssembler.assemble(posts.pageOf(recommendation.postIds, viewerId), viewerId).items
        // 고른 뒤 조립하는 사이에 숨겨지거나 지워져 하나도 남지 않으면 보여 줄 것이 없는 것이다
        val basis = if (items.isEmpty()) RecommendationBasis.NONE else recommendation.basis
        return SimilarPostsResponse(basis, items, recommendation.pending)
    }

    private companion object {
        const val LIMIT = 5
    }
}
