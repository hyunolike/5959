package com.ogu.member.presentation

import com.ogu.member.application.WithdrawalCleanupQuery
import com.ogu.member.application.WithdrawalCleanupStatus
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 계약의 `WithdrawalCleanupStatus`. 수와 때뿐이고 회원을 알아볼 것이 없다(013 FR-018). */
data class WithdrawalCleanupResponse(
    val pending: Int,
    val oldestWithdrawnAt: Instant?,
) {
    companion object {
        fun from(status: WithdrawalCleanupStatus) = WithdrawalCleanupResponse(status.pending, status.oldestWithdrawnAt)
    }
}

/**
 * 운영자가 정리가 끝나지 않은 탈퇴를 본다(013 US5-AC6). 운영자 경로라 `safety`의 `OperatorInterceptor`가 운영자가 아닌
 * 요청을 404로 막는다. 웹의 BFF는 이 경로를 넘기지 않는다.
 */
@Tag(name = "operator")
@RestController
@SecurityRequirement(name = "bearer")
class WithdrawalOperatorController(
    private val cleanupQuery: WithdrawalCleanupQuery,
) {
    @Operation(
        operationId = "getWithdrawalCleanupStatus",
        summary = "정리가 끝나지 않은 탈퇴 (013 US5-AC6). 운영자만",
        responses = [
            DocResponse(responseCode = "200", description = "끝나지 않은 탈퇴의 수와 가장 오래된 것의 때"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아님 (NOT_FOUND)"),
        ],
    )
    @GetMapping("/api/v1/operator/withdrawals/cleanup")
    fun getCleanupStatus(): ApiResponse<WithdrawalCleanupResponse> =
        ApiResponse.success(WithdrawalCleanupResponse.from(cleanupQuery.status()))
}
