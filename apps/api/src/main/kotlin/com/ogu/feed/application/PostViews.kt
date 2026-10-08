package com.ogu.feed.application

import com.ogu.emotion.AnalysisStatus
import com.ogu.emotion.EmotionView
import com.ogu.feed.presentation.dto.AuthorResponse
import com.ogu.member.MemberInfo
import com.ogu.post.PostSummary
import com.ogu.shared.text.Grapheme

/** 글 상세와 피드가 같이 쓰는 변환. 직군과 경력은 글에 남긴 스냅숏이고, 닉네임만 회원 모듈에서 가져온다(research R7). */
internal fun PostSummary.author(members: Map<Long, MemberInfo>): AuthorResponse =
    AuthorResponse(
        id = authorId,
        nickname = members[authorId]?.nickname.orEmpty(),
        jobRole = authorJobRole,
        careerYear = authorCareerYear,
    )

/** 분석 행은 글 커밋 뒤 비동기로 생기므로, 아직 없으면 `PENDING`이다. */
internal fun EmotionView?.analysisStatus(): AnalysisStatus = this?.status ?: AnalysisStatus.PENDING

/** 피드 미리보기: 앞 50글자, 더 길면 끝에 "..."(계약 `FeedItem.contentPreview`). */
internal fun previewOf(content: String): String =
    if (Grapheme.count(content) > PREVIEW_LENGTH) Grapheme.take(content, PREVIEW_LENGTH) + ELLIPSIS else content

private const val PREVIEW_LENGTH = 50
private const val ELLIPSIS = "..."
