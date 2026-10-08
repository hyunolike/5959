package com.ogu.post

/** 댓글이나 답글이 저장되거나 고쳐졌다(005 research R2). [PostWritten]과 같은 방식으로 safety 모듈이 받는다. */
data class CommentWritten(
    val postId: Long,
    val commentId: Long,
    val authorId: Long,
    val edited: Boolean,
)
