package com.ogu.post

/** 작성자가 글을 지웠다(005 research R9). 같은 트랜잭션에서 safety 모듈이 그 글의 열린 신고와 재검토 요청을 닫는다. */
data class PostRemoved(
    val postId: Long,
)
