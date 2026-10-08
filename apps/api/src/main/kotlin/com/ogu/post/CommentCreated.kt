package com.ogu.post

/** 댓글이나 답글을 달았다. 같은 트랜잭션에서 monster 모듈이 HP를 줄인다. */
data class CommentCreated(
    val postId: Long,
    val commentId: Long,
    val memberId: Long,
)
