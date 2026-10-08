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
    /** 다른 회원에게 보이지 않는 글이면 true(005 research R5). 작성자가 볼 때만 true일 수 있다. */
    val hidden: Boolean = false,
    /** 가장 최근 위험 판정의 단계 이름(`NONE`, `CONCERN`, `CRISIS`). 작성자에게만 응답에 싣는다(005 research R7). */
    val riskLevel: String = "NONE",
    val reviewRequested: Boolean = false,
)
