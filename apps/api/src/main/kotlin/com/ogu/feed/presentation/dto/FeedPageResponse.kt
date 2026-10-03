package com.ogu.feed.presentation.dto

import com.ogu.emotion.AnalysisStatus
import com.ogu.monster.MonsterView
import java.time.Instant

/** 계약의 `FeedPage`. 다음 쪽이 없으면 [nextCursor]는 null이다. */
data class FeedPageResponse(
    val items: List<FeedItemResponse>,
    val nextCursor: String?,
)

/** 계약의 `FeedItem`(US2-AC1). 분석 중이면 [monster]는 null이다. */
data class FeedItemResponse(
    val postId: Long,
    val author: AuthorResponse,
    val contentPreview: String,
    val analysisStatus: AnalysisStatus,
    val monster: MonsterView?,
    val likeCount: Int,
    val likedByMe: Boolean,
    val commentCount: Int,
    val createdAt: Instant,
)
