package com.ogu.post.presentation.dto

/**
 * 작성 결과. 감정 분석은 비동기라 막 쓴 글은 항상 `PENDING`이다. post 모듈은 emotion 모듈 타입을 모르므로 문자열로 둔다.
 */
data class PostCreatedResponse(
    val postId: Long,
    val analysisStatus: String = PENDING,
) {
    companion object {
        const val PENDING = "PENDING"
    }
}
