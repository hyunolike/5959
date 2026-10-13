package com.ogu.feed.application

import com.ogu.emotion.EmotionApi
import com.ogu.feed.presentation.dto.PublicFeedItemResponse
import com.ogu.feed.presentation.dto.PublicFeedResponse
import com.ogu.monster.MonsterApi
import com.ogu.post.PostApi
import com.ogu.post.PostPageQuery
import com.ogu.post.PostSummary
import com.ogu.safety.RiskApi
import com.ogu.safety.RiskLevel
import com.ogu.shared.text.ContentMask
import org.springframework.stereotype.Service

/**
 * 로그인하지 않은 방문자가 보는 최근 글(011 research R1~R4). 회원의 피드를 여는 것이 아니라 따로 조립한다. 응답 타입에
 * 닉네임과 회원 번호가 들어갈 자리가 없고, 회원을 읽지도 않는다.
 *
 * 누구나 보는 자리라 회원의 피드보다 좁게 고른다. 지우지도 숨기지도 않았고, AI 분류까지 끝나 위험 없음으로 판정된 글만
 * 내보낸다([RiskApi.clearedPostIds]). 회원에게는 보이는 우려 글, AI 분류를 기다리는 글, 분류가 끝내 실패해 키워드로만
 * 닫힌 글, 판정 기록이 없는 옛 글이 여기서 빠진다(원칙 IV). 욕설은 언제나 가린다.
 * 최근 [LIMIT]개까지만 주고 넘겨 볼 방법을 두지 않는다. 쿼리는 4개다.
 */
@Service
class PublicFeedQuery(
    private val postApi: PostApi,
    private val riskApi: RiskApi,
    private val emotionApi: EmotionApi,
    private val monsterApi: MonsterApi,
    private val contentMask: ContentMask,
) {
    fun get(): PublicFeedResponse {
        // 걸러질 글을 감안해 넉넉히 읽는다. 보는 회원이 없으므로 공감 여부는 쓰지 않는다
        val candidates = postApi.page(PostPageQuery(viewerId = NOBODY, size = CANDIDATES)).items.map { it.post }
        val calm = candidates.filter { !it.hidden && RiskLevel.of(it.riskLevel) == RiskLevel.NONE }
        // 글의 단계 열은 분류 결과보다 늦게 따라올 수 있다. 내보낼 글은 판정 기록이 허락한 것만이다
        val cleared = riskApi.clearedPostIds(calm.map { it.postId })
        val posts = calm.filter { it.postId in cleared }.take(LIMIT)

        val postIds = posts.map { it.postId }
        val monsters = monsterApi.findByPostIds(postIds)
        val emotions = emotionApi.findByPostIds(postIds)
        return PublicFeedResponse(
            posts.map { post ->
                PublicFeedItemResponse(
                    postId = post.postId,
                    jobRole = post.authorJobRole,
                    careerYear = post.authorCareerYear,
                    contentPreview = previewOf(contentMask.mask(post.content)),
                    analysisStatus = emotions[post.postId].analysisStatus(),
                    monster = monsters[post.postId],
                    likeCount = post.likeCount,
                    commentCount = post.commentCount,
                    createdAt = post.createdAt,
                )
            },
        )
    }

    private companion object {
        /** 어떤 회원의 번호도 아닌 값. 번호는 1부터 매겨진다. */
        const val NOBODY = 0L
        const val LIMIT = 20
        const val CANDIDATES = PostPageQuery.MAX_SIZE
    }
}
