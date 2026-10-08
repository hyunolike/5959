package com.ogu.post

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/** 마이페이지 "내 댓글" 한 쪽(004 research R12). 다음 쪽이 없으면 [nextCursor]는 null이다. */
data class MyCommentPage(
    val items: List<MyComment>,
    val nextCursor: String?,
)

/**
 * 내가 쓴 살아 있는 댓글 하나와 그 글의 앞 50글자. 계약(`MyComment`)에서 답글 여부의 이름은 `reply`라
 * HTTP 응답에서는 그 이름으로 내보낸다.
 */
data class MyComment(
    val commentId: Long,
    val postId: Long,
    val postContentPreview: String,
    val content: String,
    @get:JsonProperty("reply")
    val isReply: Boolean,
    val createdAt: Instant,
)
