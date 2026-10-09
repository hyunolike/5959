package com.ogu.safety

import com.ogu.post.ContentType

/** 운영자가 숨김을 풀었다(005 research R8). 커밋 뒤 notification 모듈이 작성자에게 알린다. */
data class ContentRestored(
    val targetType: ContentType,
    val targetId: Long,
    val postId: Long,
    val authorId: Long,
    val actionId: Long,
)
