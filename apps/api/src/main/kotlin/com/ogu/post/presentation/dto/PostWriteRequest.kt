package com.ogu.post.presentation.dto

import com.ogu.post.CommentTone

/** 필드가 빠지면 null로 받아 서비스 앞에서 400으로 거절한다. 목록에 없는 말투는 역직렬화에서 400이 된다. */
data class PostWriteRequest(
    val content: String? = null,
    val commentTone: CommentTone? = null,
)

/** 글 수정 요청(계약의 `PostUpdateRequest`). 둘 다 빠지면 서비스에서 400으로 거절한다. 빠진 쪽은 그대로 둔다. */
data class PostUpdateRequest(
    val content: String? = null,
    val commentTone: CommentTone? = null,
)
