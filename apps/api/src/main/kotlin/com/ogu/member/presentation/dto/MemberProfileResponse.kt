package com.ogu.member.presentation.dto

import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.member.domain.AuthMethod
import com.ogu.member.domain.Member
import java.time.Instant

/** 계약의 `MemberProfile`. */
data class MemberProfileResponse(
    val id: Long,
    val authMethod: AuthMethod,
    val email: String?,
    val nickname: String?,
    val jobRole: JobRole?,
    val careerYear: CareerYear?,
    val onboarded: Boolean,
    /**
     * 닉네임을 다시 바꿀 수 있게 되는 때(014). null이거나 지난 때면 지금 바꿀 수 있다. 본인에게만 나가는 값이다.
     * 지난 값을 null로 바꿔 주지 않는다. 읽는 쪽이 지금과 견준다.
     */
    val nicknameChangeAvailableAt: Instant?,
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
                nicknameChangeAvailableAt = member.nicknameLockedUntil,
            )
    }
}
