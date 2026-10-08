package com.ogu.feed.application

import com.ogu.feed.presentation.dto.FeedPageResponse
import com.ogu.post.PostApi
import com.ogu.post.PostPageQuery
import org.springframework.stereotype.Service

/**
 * 피드 한 쪽을 파사드로 모은다(research R7). 글 쪽(공감 여부 포함)을 읽고 [FeedAssembler]가 몬스터, 감정 분석, 작성자를
 * 붙여 쿼리 수가 쪽 크기와 상관없이 4개다.
 */
@Service
class FeedQuery(
    private val postApi: PostApi,
    private val feedAssembler: FeedAssembler,
) {
    fun get(query: PostPageQuery): FeedPageResponse = feedAssembler.assemble(postApi.page(query), query.viewerId)
}
