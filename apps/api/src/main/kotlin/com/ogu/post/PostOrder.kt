package com.ogu.post

/** 피드 정렬(FR-011). 최신순은 글 ID 내림차순, 인기순은 공감 수 내림차순이고 같으면 최신 글부터다(research R7). */
enum class PostOrder {
    LATEST,
    POPULAR,
}
