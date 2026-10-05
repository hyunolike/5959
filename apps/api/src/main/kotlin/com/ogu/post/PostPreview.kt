package com.ogu.post

/** 알림 목록에 붙이는 글 미리보기. 지운 글도 [deleted]로 함께 준다(US2-AC5). [contentPreview]는 앞 50글자다. */
data class PostPreview(
    val postId: Long,
    val contentPreview: String,
    val deleted: Boolean,
)
