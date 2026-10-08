package com.ogu.feed.application

import com.ogu.emotion.EmotionApi
import com.ogu.feed.presentation.dto.PostDetailResponse
import com.ogu.member.MemberApi
import com.ogu.monster.MonsterApi
import com.ogu.post.PostApi
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Service

/**
 * 글 상세를 파사드로 모은다(research R1). feed는 자기 테이블이 없고 다른 모듈 테이블을 직접 읽지 않는다.
 * 분석 행은 글 커밋 뒤 비동기로 생기므로, 아직 없으면 `PENDING`으로 보여 준다.
 */
@Service
class PostDetailQuery(
    private val postApi: PostApi,
    private val emotionApi: EmotionApi,
    private val monsterApi: MonsterApi,
    private val memberApi: MemberApi,
) {
    fun get(
        postId: Long,
        viewerId: Long,
    ): PostDetailResponse {
        val post = postApi.find(postId) ?: throw BusinessException(ErrorCode.POST_NOT_FOUND)
        val ids = listOf(postId)
        return PostDetailResponse(
            postId = post.postId,
            author = post.author(memberApi.getMembers(listOf(post.authorId))),
            content = post.content,
            commentTone = post.commentTone,
            analysisStatus = emotionApi.findByPostIds(ids)[postId].analysisStatus(),
            monster = monsterApi.findByPostIds(ids)[postId],
            likeCount = post.likeCount,
            likedByMe = postId in postApi.likedPostIds(viewerId, ids),
            commentCount = post.commentCount,
            mine = post.authorId == viewerId,
            myCommentCounted = monsterApi.hasCountedComment(postId, viewerId),
            createdAt = post.createdAt,
        )
    }
}
