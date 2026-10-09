package com.ogu.feed.presentation.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.ogu.emotion.AnalysisStatus
import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.monster.MonsterView
import com.ogu.post.CommentTone
import com.ogu.post.ContentSafety
import java.time.Instant

/** 계약의 `PostDetail`(FR-012). 분석 중이면 [monster]는 null이다. */
data class PostDetailResponse(
    val postId: Long,
    val author: AuthorResponse,
    val content: String,
    val commentTone: CommentTone,
    val analysisStatus: AnalysisStatus,
    val monster: MonsterView?,
    val likeCount: Int,
    val likedByMe: Boolean,
    val commentCount: Int,
    val mine: Boolean,
    val myCommentCounted: Boolean,
    /** 내 글일 때만 실린다. 다른 회원의 응답에는 필드가 없다(005 research R7). */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val safety: ContentSafety?,
    val createdAt: Instant,
)

/** 계약의 `Author`. 직군과 경력은 글을 쓸 때의 스냅숏이다(research R7). */
data class AuthorResponse(
    val id: Long,
    val nickname: String,
    val jobRole: JobRole,
    val careerYear: CareerYear,
)
