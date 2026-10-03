package com.ogu.post

import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import java.time.Instant

/** 다른 모듈에 보여 주는 살아 있는 글. 작성자 직군과 경력은 작성 시점 스냅숏이다(research R7). */
data class PostSummary(
    val postId: Long,
    val authorId: Long,
    val authorJobRole: JobRole,
    val authorCareerYear: CareerYear,
    val content: String,
    val commentTone: CommentTone,
    val likeCount: Int,
    val commentCount: Int,
    val createdAt: Instant,
)
