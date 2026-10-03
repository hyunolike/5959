package com.ogu.post

/** 댓글에 공감했다. 같은 트랜잭션에서 monster 모듈이 HP를 줄인다. */
data class CommentLiked(
    val postId: Long,
    val commentId: Long,
    val memberId: Long,
)
