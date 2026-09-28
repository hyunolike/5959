package com.ogu.member.presentation.dto

import com.ogu.member.CareerYear
import com.ogu.member.JobRole

data class OnboardingRequest(
    val nickname: String,
    val jobRole: JobRole,
    val careerYear: CareerYear,
)
