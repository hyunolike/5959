package com.ogu.raid.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.raid.application.RaidAttackResult
import com.ogu.raid.application.RaidAttackService
import com.ogu.raid.application.RaidQueryService
import com.ogu.raid.application.RaidState
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 계약의 `RaidAttackRequest`. 필드가 빠지면 null로 받아 400으로 거절한다. */
data class RaidAttackRequest(
    val bossId: Long? = null,
)

/** 보스 레이드(006). 응답에는 보스의 상태와 요청한 회원 자신의 기여만 있다. 온보딩 전 회원은 `OnboardingGuard`가 막는다. */
@Tag(name = "raid")
@RestController
@SecurityRequirement(name = "bearer")
class RaidController(
    private val queryService: RaidQueryService,
    private val attackService: RaidAttackService,
) {
    @Operation(
        operationId = "getRaid",
        summary = "지금의 레이드 (US1-AC1, US4-AC3, US5-AC3)",
        responses = [
            DocResponse(responseCode = "200", description = "레이드 상태. 공격을 받을 수 없으면 available이 false다"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @GetMapping("/api/v1/raid")
    fun getRaid(member: AuthenticatedMember): ApiResponse<RaidState> {
        val state = queryService.state(member.memberId)
        return ApiResponse.success(state)
    }

    @Operation(
        operationId = "attackRaidBoss",
        summary = "보스 공격 (US1-AC2~AC6). HP를 1 줄인다. 회원마다 1초에 한 번",
        responses = [
            DocResponse(responseCode = "200", description = "받아들임"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "409", description = "보스가 끝났거나 지금 보스가 아님 (RAID_BOSS_ENDED)"),
            DocResponse(responseCode = "429", description = "쿨다운 안 (RAID_COOLDOWN)"),
            DocResponse(responseCode = "503", description = "공격을 받을 수 없음 (RAID_UNAVAILABLE)"),
        ],
    )
    @PostMapping("/api/v1/raid/attacks")
    fun attackRaidBoss(
        member: AuthenticatedMember,
        @RequestBody request: RaidAttackRequest,
    ): ApiResponse<RaidAttackResult> {
        val bossId = request.bossId ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "공격할 보스를 주세요.")
        return ApiResponse.success(attackService.attack(member.memberId, bossId))
    }
}
