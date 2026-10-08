package com.ogu.post

/**
 * 알림의 받는 사람을 정할 때 쓰는 살아 있는 댓글(004 research R6). 원 댓글이면 [parentId]와 [parentAuthorId]가 null이다.
 */
data class CommentSummary(
    val postId: Long,
    val authorId: Long,
    val parentId: Long?,
    val parentAuthorId: Long?,
)
