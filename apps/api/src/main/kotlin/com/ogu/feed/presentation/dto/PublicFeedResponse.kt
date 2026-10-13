package com.ogu.feed.presentation.dto

import com.ogu.emotion.AnalysisStatus
import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.monster.MonsterView
import java.time.Instant

/** 계약의 `PublicFeed`(011). 최근 글 최대 20개다. 다음 쪽이 없다. 넘겨 볼 수 없게 커서를 두지 않는다. */
data class PublicFeedResponse(
    val items: List<PublicFeedItemResponse>,
)

/**
 * 계약의 `PublicFeedItem`(011 research R4). 로그인하지 않은 방문자에게 보이는 글 하나다. 닉네임, 회원 번호, 내 공감 여부,
 * 숨김 여부가 들어갈 자리가 없다(FR-003). 직군과 경력은 글에 적힌 스냅숏이다.
 */
data class PublicFeedItemResponse(
    val postId: Long,
    val jobRole: JobRole,
    val careerYear: CareerYear,
    val contentPreview: String,
    val analysisStatus: AnalysisStatus,
    val monster: MonsterView?,
    val likeCount: Int,
    val commentCount: Int,
    val createdAt: Instant,
)
