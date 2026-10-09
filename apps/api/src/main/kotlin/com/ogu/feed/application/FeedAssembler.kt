package com.ogu.feed.application

import com.ogu.emotion.EmotionApi
import com.ogu.feed.presentation.dto.FeedItemResponse
import com.ogu.feed.presentation.dto.FeedPageResponse
import com.ogu.member.MemberApi
import com.ogu.monster.MonsterApi
import com.ogu.post.PostPage
import com.ogu.shared.text.ContentMask
import com.ogu.shared.text.maskFor
import org.springframework.stereotype.Component

/**
 * 글 한 쪽에 몬스터, 감정 분석, 작성자를 한 번씩 일괄로 붙여 피드 항목으로 만든다(research R7). 피드와 마이페이지의
 * "내가 쓴 글", "공감한 글"이 함께 써서 항목 모양이 갈라지지 않는다(004 research R12). 쿼리는 쪽 크기와 상관없이 3개다.
 */
@Component
class FeedAssembler(
    private val emotionApi: EmotionApi,
    private val monsterApi: MonsterApi,
    private val memberApi: MemberApi,
    private val contentMask: ContentMask,
) {
    /** 다른 회원의 글은 욕설을 가린 뒤 미리보기로 자른다. 잘린 욕설이 남지 않는다(005 US5-AC1, AC5). */
    fun assemble(
        page: PostPage,
        viewerId: Long,
    ): FeedPageResponse {
        val postIds = page.items.map { it.post.postId }
        val monsters = monsterApi.findByPostIds(postIds)
        val emotions = emotionApi.findByPostIds(postIds)
        val members = memberApi.getMembers(page.items.map { it.post.authorId }.toSet())
        val items =
            page.items.map { (post, likedByMe) ->
                FeedItemResponse(
                    postId = post.postId,
                    author = post.author(members),
                    contentPreview = previewOf(contentMask.maskFor(viewerId, post.authorId, post.content)),
                    analysisStatus = emotions[post.postId].analysisStatus(),
                    monster = monsters[post.postId],
                    likeCount = post.likeCount,
                    likedByMe = likedByMe,
                    commentCount = post.commentCount,
                    hidden = post.hidden,
                    createdAt = post.createdAt,
                )
            }
        return FeedPageResponse(items, page.nextCursor)
    }
}
