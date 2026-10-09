package com.ogu.post

import java.time.Instant

/**
 * safety 모듈이 판정하고 처리하려고 읽는 글이나 댓글 하나(005 data-model.md). [content]는 가리지 않은 원문이다.
 * [postId]는 대상이 댓글이면 그 댓글의 글이다. [updatedAt]은 판정한 내용의 판(版)을 가리는 데 쓴다.
 */
data class ModerationTarget(
    val type: ContentType,
    val id: Long,
    val postId: Long,
    val authorId: Long,
    val content: String,
    val updatedAt: Instant,
    val hidden: Boolean,
)
