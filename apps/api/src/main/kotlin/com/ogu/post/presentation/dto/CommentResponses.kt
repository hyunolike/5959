package com.ogu.post.presentation.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.post.ContentSafety
import java.time.Instant

/** 댓글 작성 요청. 필드가 빠지면 null로 받아 서비스 앞에서 400으로 거절한다. [parentId]가 있으면 답글이다. */
data class CommentWriteRequest(
    val content: String? = null,
    val parentId: Long? = null,
)

/** 댓글 수정 요청. 본문이 빠지면 null로 받아 서비스 앞에서 400으로 거절한다. */
data class CommentUpdateRequest(
    val content: String? = null,
)

/**
 * 계약의 `Comment`. 답글의 [replies]는 항상 비어 있다. 숨긴 댓글을 다른 회원이 보면 [hidden]이 true이고 [author]와
 * [content]가 null이다. [safety]는 내 댓글일 때만 실리고 다른 회원의 응답에는 필드가 없다(005 research R5, R7).
 */
data class CommentResponse(
    val commentId: Long,
    val author: CommentAuthorResponse?,
    val content: String?,
    val hidden: Boolean,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val safety: ContentSafety?,
    val likeCount: Int,
    val likedByMe: Boolean,
    val mine: Boolean,
    val createdAt: Instant,
    val replies: List<CommentResponse>,
)

/** 계약의 `Author`. 댓글은 글과 달리 스냅숏이 없어 지금 프로필을 보여 준다. */
data class CommentAuthorResponse(
    val id: Long,
    val nickname: String,
    val jobRole: JobRole?,
    val careerYear: CareerYear?,
)

/** 계약의 `CommentPage`. 원 댓글(오래된 순)과 각 답글. 다음 쪽이 없으면 [nextCursor]는 null이다. */
data class CommentPageResponse(
    val items: List<CommentResponse>,
    val nextCursor: String?,
)
