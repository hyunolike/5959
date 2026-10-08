package com.ogu.post.presentation.dto

/** 계약의 `LikeResult`. 공감이나 취소 뒤의 공감 수와 내 공감 여부다. */
data class LikeResultResponse(
    val likeCount: Int,
    val likedByMe: Boolean,
)
