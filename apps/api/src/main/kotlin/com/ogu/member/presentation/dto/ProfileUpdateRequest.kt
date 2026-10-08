package com.ogu.member.presentation.dto

import com.ogu.member.CareerYear
import com.ogu.member.JobRole

/** 계약의 `ProfileUpdateRequest`. 보낸 항목만 바꾸고, 하나도 없으면 400이다. */
data class ProfileUpdateRequest(
    val nickname: String? = null,
    val jobRole: JobRole? = null,
    val careerYear: CareerYear? = null,
)
