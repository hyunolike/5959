package com.ogu.safety

import com.ogu.post.ContentType

/**
 * 글이나 댓글의 위험 단계가 올라갔다(005 research R7). 커밋 뒤 notification 모듈이 작성자에게 도움 안내 알림을 만든다.
 * 같은 대상에 같은 단계로는 한 번만 나간다. 본문과 판정 근거는 싣지 않는다.
 */
data class RiskDetected(
    val targetType: ContentType,
    val targetId: Long,
    val postId: Long,
    val authorId: Long,
    val level: RiskLevel,
)
