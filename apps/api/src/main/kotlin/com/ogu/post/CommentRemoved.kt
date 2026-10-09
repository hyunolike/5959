package com.ogu.post

/** 댓글이 지워졌다(005 research R9). 원 댓글을 지우면 함께 지워진 답글마다 하나씩 나간다. */
data class CommentRemoved(
    val commentId: Long,
)
