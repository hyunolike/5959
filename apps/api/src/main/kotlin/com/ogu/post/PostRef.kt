package com.ogu.post

import java.time.Instant

/** 감정 통계용 살아 있는 글의 ID와 작성 시각(004 research R12). */
data class PostRef(
    val postId: Long,
    val createdAt: Instant,
)
