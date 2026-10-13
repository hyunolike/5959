package com.ogu.member

/**
 * 다른 모듈이 회원 정보를 읽을 때 쓰는 공개 타입. 온보딩 전에는 nickname/jobRole/careerYear가 null이다.
 * [ai]가 true면 AI 친구다(009 research R2). 닉네임만 있고 직군과 경력은 언제나 null이다.
 * [withdrawn]이 true면 탈퇴한 회원이다(013 research R1). 번호만 남고 닉네임, 직군, 경력이 모두 null이다.
 */
data class MemberInfo(
    val id: Long,
    val nickname: String?,
    val jobRole: JobRole?,
    val careerYear: CareerYear?,
    val ai: Boolean = false,
    val withdrawn: Boolean = false,
)
