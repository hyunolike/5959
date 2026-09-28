package com.ogu.member.presentation.dto

import com.ogu.member.application.OnboardingResult
import java.time.Instant

/** 계약의 `OnboardingResult`. [accessToken]은 `onboarded=true` 클레임을 담은 새 토큰이다. */
data class OnboardingResultResponse(
    val member: MemberProfileResponse,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
) {
    companion object {
        fun from(result: OnboardingResult) =
            OnboardingResultResponse(
                member = MemberProfileResponse.from(result.member),
                accessToken = result.accessToken.value,
                accessTokenExpiresAt = result.accessToken.expiresAt,
            )
    }
}
