package com.ogu.post

/** 글에 공감했다. 같은 트랜잭션에서 monster 모듈이 HP를 줄인다. */
data class PostLiked(
    val postId: Long,
    val memberId: Long,
)
