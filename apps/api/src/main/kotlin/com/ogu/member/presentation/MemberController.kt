package com.ogu.member.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.member.application.MemberQueryService
import com.ogu.member.application.OnboardingService
import com.ogu.member.presentation.dto.MemberProfileResponse
import com.ogu.member.presentation.dto.NicknameAvailabilityResponse
import com.ogu.member.presentation.dto.OnboardingRequest
import com.ogu.member.presentation.dto.OnboardingResultResponse
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/**
 * 내 프로필, 닉네임 확인, 온보딩. 세 경로 모두 온보딩 전에도 부를 수 있다(research R9, OnboardingGuard 허용 목록).
 */
@Tag(name = "member")
@RestController
@RequestMapping("/api/v1/members")
@SecurityRequirement(name = "bearer")
class MemberController(
    private val memberQueryService: MemberQueryService,
    private val onboardingService: OnboardingService,
) {
    @Operation(
        operationId = "getMe",
        summary = "내 프로필 (FR-015). 온보딩 전에도 호출할 수 있다",
        responses = [
            DocResponse(responseCode = "200", description = "내 프로필"),
            DocResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION),
        ],
    )
    @GetMapping("/me")
    fun getMe(member: AuthenticatedMember): ApiResponse<MemberProfileResponse> =
        ApiResponse.success(MemberProfileResponse.from(memberQueryService.getProfile(member.memberId)))

    @Operation(
        operationId = "checkNickname",
        summary = "닉네임 사용 가능 여부 (US1-AC5, US1-AC6)",
        responses = [
            DocResponse(responseCode = "200", description = "사용 가능 여부"),
            DocResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION),
        ],
    )
    @GetMapping("/nickname-availability")
    fun checkNickname(
        @RequestParam nickname: String,
    ): ApiResponse<NicknameAvailabilityResponse> =
        ApiResponse.success(NicknameAvailabilityResponse.from(onboardingService.checkNickname(nickname)))

    @Operation(
        operationId = "completeOnboarding",
        summary = "온보딩 완료 (US1-AC4~AC6)",
        responses = [
            DocResponse(responseCode = "200", description = "온보딩 완료. 새 access 토큰을 함께 준다"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION),
            DocResponse(
                responseCode = "409",
                description = "닉네임 중복(NICKNAME_TAKEN) 또는 이미 온보딩함(ALREADY_ONBOARDED)",
            ),
        ],
    )
    @PutMapping("/me/onboarding")
    fun completeOnboarding(
        member: AuthenticatedMember,
        @RequestBody request: OnboardingRequest,
    ): ApiResponse<OnboardingResultResponse> {
        val result =
            onboardingService.complete(
                memberId = member.memberId,
                sessionId = member.sessionId,
                nickname = request.nickname,
                jobRole = request.jobRole,
                careerYear = request.careerYear,
            )
        return ApiResponse.success(OnboardingResultResponse.from(result))
    }

    private companion object {
        const val UNAUTHORIZED_DESCRIPTION = "인증 없음 또는 세션 만료, 무효 (UNAUTHORIZED, SESSION_EXPIRED)"
    }
}
