package com.ogu.member.presentation.dto

/** 계약의 `WithdrawalRequest`. [confirmed]가 true가 아니면 탈퇴하지 않는다(013 FR-003). */
data class WithdrawalRequest(
    val confirmed: Boolean? = null,
)
