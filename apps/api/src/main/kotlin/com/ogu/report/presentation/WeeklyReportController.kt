package com.ogu.report.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.report.application.WeeklyReportQueryService
import com.ogu.report.presentation.dto.WeeklyReportPageResponse
import com.ogu.report.presentation.dto.WeeklyReportResponse
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 내 주간 리포트(008). 경로에 회원이 없다. 늘 요청한 회원의 것만 돌려준다. */
@Tag(name = "mypage")
@RestController
@SecurityRequirement(name = "bearer")
class WeeklyReportController(
    private val queryService: WeeklyReportQueryService,
) {
    @Operation(
        operationId = "getMyWeeklyReports",
        summary = "내 주간 리포트 목록, 최신 주부터 (008 US4-AC1, US4-AC3)",
        responses = [
            DocResponse(responseCode = "200", description = "받은 리포트가 없으면 빈 목록이다"),
            DocResponse(responseCode = "400", description = "커서나 크기가 올바르지 않음 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @GetMapping("/api/v1/members/me/weekly-reports")
    fun getMyWeeklyReports(
        member: AuthenticatedMember,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<WeeklyReportPageResponse> =
        ApiResponse.success(WeeklyReportPageResponse.from(queryService.page(member.memberId, cursor, size)))

    @Operation(
        operationId = "getMyWeeklyReport",
        summary = "한 주의 내 리포트 (008 US1-AC3, US1-AC9)",
        responses = [
            DocResponse(responseCode = "200", description = "리포트"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "그 주의 내 리포트가 없음 (WEEKLY_REPORT_NOT_FOUND)"),
        ],
    )
    @GetMapping("/api/v1/members/me/weekly-reports/{weekStart}")
    fun getMyWeeklyReport(
        member: AuthenticatedMember,
        // 날짜가 아닌 값도 400이 아니라 같은 404로 답하려고 문자열로 받는다
        @PathVariable weekStart: String,
    ): ApiResponse<WeeklyReportResponse> =
        ApiResponse.success(WeeklyReportResponse.from(queryService.get(member.memberId, weekStart)))
}
