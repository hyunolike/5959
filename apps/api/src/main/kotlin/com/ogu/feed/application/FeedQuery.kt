package com.ogu.feed.application

import com.ogu.emotion.EmotionApi
import com.ogu.feed.presentation.dto.FeedItemResponse
import com.ogu.feed.presentation.dto.FeedPageResponse
import com.ogu.member.MemberApi
import com.ogu.monster.MonsterApi
import com.ogu.post.PostApi
import com.ogu.post.PostPageQuery
import org.springframework.stereotype.Service

/**
 * 피드 한 쪽을 파사드로 모은다(research R7). 글 쪽(공감 여부 포함), 몬스터, 감정 분석, 작성자를 한 번씩 일괄로 읽어
 * 쿼리 수가 쪽 크기와 상관없이 4개다.
 */
@Service
class FeedQuery(
    private val postApi: PostApi,
    private val emotionApi: EmotionApi,
    private val monsterApi: MonsterApi,
    private val memberApi: MemberApi,
) {
    fun get(query: PostPageQuery): FeedPageResponse {
        val page = postApi.page(query)
        val postIds = page.items.map { it.post.postId }
        val monsters = monsterApi.findByPostIds(postIds)
        val emotions = emotionApi.findByPostIds(postIds)
        val members = memberApi.getMembers(page.items.map { it.post.authorId }.toSet())
        val items =
            page.items.map { (post, likedByMe) ->
                FeedItemResponse(
                    postId = post.postId,
                    author = post.author(members),
                    contentPreview = previewOf(post.content),
                    analysisStatus = emotions[post.postId].analysisStatus(),
                    monster = monsters[post.postId],
                    likeCount = post.likeCount,
                    likedByMe = likedByMe,
                    commentCount = post.commentCount,
                    createdAt = post.createdAt,
                )
            }
        return FeedPageResponse(items, page.nextCursor)
    }
}
