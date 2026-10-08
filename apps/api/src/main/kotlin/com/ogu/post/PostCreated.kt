package com.ogu.post

import java.time.Instant

/**
 * 글이 저장됐다. 커밋 뒤 비동기로 emotion 모듈이 분석을 예약한다. 이벤트는 event_publication에 직렬화돼 남으므로
 * 본문은 싣지 않는다. 분석기는 [PostApi.find]로 본문을 다시 읽는다.
 */
data class PostCreated(
    val postId: Long,
    val authorId: Long,
    val createdAt: Instant,
)
