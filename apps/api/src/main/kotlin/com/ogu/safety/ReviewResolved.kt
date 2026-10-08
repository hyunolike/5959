package com.ogu.safety

import com.ogu.post.ContentType

/**
 * 운영자가 재검토 요청을 닫았다(005 research R11). [kept]이면 숨김을 유지한 것이고, notification 모듈이 작성자에게 결과를
 * 알린다. 풀었으면 [ContentRestored]가 따로 나간다.
 */
data class ReviewResolved(
    val requestId: Long,
    val targetType: ContentType,
    val targetId: Long,
    val postId: Long,
    val requesterId: Long,
    val kept: Boolean,
)
