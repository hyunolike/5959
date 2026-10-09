package com.ogu.post

/**
 * 계약의 `ContentSafety`(005 research R7). 글이나 댓글의 작성자에게만 응답에 싣는다. 다른 회원의 응답에는 필드가 없다.
 * [level]은 위험 단계 이름(`NONE`, `CONCERN`, `CRISIS`)이다.
 */
data class ContentSafety(
    val level: String,
    val hidden: Boolean,
    val reviewRequested: Boolean,
)
