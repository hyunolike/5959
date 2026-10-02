package com.ogu.member

/**
 * 다른 모듈이 회원 정보를 읽을 때 쓰는 공개 타입. 온보딩 전에는 nickname/jobRole/careerYear가 null이다.
 */
data class MemberInfo(
    val id: Long,
    val nickname: String?,
    val jobRole: JobRole?,
    val careerYear: CareerYear?,
)
