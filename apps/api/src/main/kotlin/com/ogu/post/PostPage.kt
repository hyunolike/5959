package com.ogu.post

import com.ogu.member.CareerYear
import com.ogu.member.JobRole

/**
 * 피드 한 쪽을 고르는 조건(FR-011). 직군은 하나라도 맞으면, 경력도 하나라도 맞으면 통과하고, 둘은 모두 맞아야 한다.
 * 빈 집합은 거르지 않는다는 뜻이다. [cursor]는 앞 쪽의 [PostPage.nextCursor]를 그대로 넘긴다.
 */
data class PostPageQuery(
    val viewerId: Long,
    val order: PostOrder = PostOrder.LATEST,
    val jobRoles: Set<JobRole> = emptySet(),
    val careerYears: Set<CareerYear> = emptySet(),
    val cursor: String? = null,
    val size: Int = DEFAULT_SIZE,
) {
    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 50
    }
}

/** 피드 한 쪽. 다음 쪽이 없으면 [nextCursor]는 null이다. 커서는 불투명 문자열이다(research R7). */
data class PostPage(
    val items: List<PostPageItem>,
    val nextCursor: String?,
)

/** 피드의 글 하나와, 보는 회원이 그 글에 공감했는지. */
data class PostPageItem(
    val post: PostSummary,
    val likedByMe: Boolean,
)
