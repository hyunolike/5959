package com.ogu.member.presentation.dto

import com.ogu.member.application.NicknameAvailability
import com.ogu.member.application.NicknameUnavailableReason

/** 계약의 `NicknameAvailability`. 사용할 수 있으면 [reason]은 null이다. */
data class NicknameAvailabilityResponse(
    val available: Boolean,
    val reason: NicknameUnavailableReason?,
) {
    companion object {
        fun from(availability: NicknameAvailability) =
            NicknameAvailabilityResponse(available = availability.available, reason = availability.reason)
    }
}
