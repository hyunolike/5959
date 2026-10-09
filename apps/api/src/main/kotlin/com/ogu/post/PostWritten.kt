package com.ogu.post

/**
 * 글이 저장되거나 고쳐졌다(005 research R2). 같은 트랜잭션에서 safety 모듈이 키워드 규칙으로 판정하고, 위기면 그 자리에서
 * 숨긴다. 이벤트는 event_publication에 직렬화돼 남으므로 본문은 싣지 않는다.
 */
data class PostWritten(
    val postId: Long,
    val authorId: Long,
    val edited: Boolean,
)
