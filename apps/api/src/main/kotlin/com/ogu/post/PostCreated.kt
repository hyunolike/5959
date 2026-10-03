package com.ogu.post

import java.time.Instant

/** 글이 저장됐다. 커밋 뒤 비동기로 emotion 모듈이 분석을 예약한다. */
data class PostCreated(
    val postId: Long,
    val authorId: Long,
    val content: String,
    val createdAt: Instant,
)
