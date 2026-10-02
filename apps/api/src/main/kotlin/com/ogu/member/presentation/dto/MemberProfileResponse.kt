package com.ogu.member.presentation.dto

import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.member.domain.AuthMethod
import com.ogu.member.domain.Member

/** 계약의 `MemberProfile`. */
data class MemberProfileResponse(
    val id: Long,
    val authMethod: AuthMethod,
    val email: String?,
    val nickname: String?,
    val jobRole: JobRole?,
    val careerYear: CareerYear?,
    val onboarded: Boolean,
) {
    companion object {
        fun from(member: Member) =
            MemberProfileResponse(
                id = member.id,
                authMethod = member.authMethod,
                email = member.email,
                nickname = member.nickname,
                jobRole = member.jobRole,
                careerYear = member.careerYear,
                onboarded = member.isOnboarded,
            )
    }
}
