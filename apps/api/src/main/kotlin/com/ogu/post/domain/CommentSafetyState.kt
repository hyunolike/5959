package com.ogu.post.domain

/** 댓글의 숨김 여부, 위험 단계 이름, 재검토 요청 여부(005 research R5, R7). */
data class CommentSafetyState(
    val hidden: Boolean,
    val riskLevel: String,
    val reviewRequested: Boolean,
)
