package com.ogu.member.presentation.dto

/** 계약의 `PasswordResetAvailability`. */
data class PasswordResetAvailabilityResponse(
    val available: Boolean,
)

/** 계약의 `PasswordResetRequest`. 필드가 빠지면 null로 받아 400으로 거절한다. */
data class PasswordResetRequest(
    val email: String? = null,
)

/** 계약의 `PasswordResetConfirmRequest`. */
data class PasswordResetConfirmRequest(
    val token: String? = null,
    val password: String? = null,
)
