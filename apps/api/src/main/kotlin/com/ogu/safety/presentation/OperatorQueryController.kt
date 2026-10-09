package com.ogu.safety.presentation

import com.ogu.safety.RiskLevel
import com.ogu.safety.application.OperatorAssessment
import com.ogu.safety.application.OperatorPage
import com.ogu.safety.application.OperatorQueryService
import com.ogu.safety.application.OperatorReport
import com.ogu.safety.application.OperatorReview
import com.ogu.safety.application.TermService
import com.ogu.safety.domain.TermKind
import com.ogu.safety.domain.TermRow
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

enum class ReportStatus {
    PENDING,
    RESOLVED,
    REJECTED,
    CLOSED,
}

enum class ReviewStatus {
    PENDING,
    KEPT,
    RESTORED,
}

/** 운영자 조회(005 US4). [OperatorInterceptor]가 운영자가 아닌 요청을 404로 막는다. 응답의 본문은 가리지 않은 원문이다. */
@Tag(name = "operator")
@RestController
@RequestMapping(OPERATOR_PATH)
@SecurityRequirement(name = "bearer")
class OperatorQueryController(
    private val queryService: OperatorQueryService,
    private val termService: TermService,
) {
    @Operation(
        operationId = "listAssessments",
        summary = "위험 판정 조회 (US4-AC1). 최신순. 기본은 단계가 NONE이 아닌 것",
        responses = [
            DocResponse(responseCode = "200", description = "판정 페이지"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아님 (NOT_FOUND)"),
        ],
    )
    @GetMapping("/assessments")
    fun listAssessments(
        @RequestParam(required = false) level: RiskLevel?,
        @RequestParam(required = false) reviewed: Boolean?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<OperatorPage<OperatorAssessment>> {
        val page = queryService.assessments(level, reviewed, cursor, size)
        return ApiResponse.success(page)
    }

    @Operation(
        operationId = "listReports",
        summary = "신고 조회 (US4-AC2). 최신순. 기본은 PENDING",
        responses = [
            DocResponse(responseCode = "200", description = "신고 페이지"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아님 (NOT_FOUND)"),
        ],
    )
    @GetMapping("/reports")
    fun listReports(
        @RequestParam(defaultValue = "PENDING") status: ReportStatus,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<OperatorPage<OperatorReport>> = ApiResponse.success(queryService.reports(status.name, cursor, size))

    @Operation(
        operationId = "listReviewRequests",
        summary = "재검토 요청 조회 (US4-AC8). 최신순. 기본은 PENDING",
        responses = [
            DocResponse(responseCode = "200", description = "재검토 요청 페이지"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아님 (NOT_FOUND)"),
        ],
    )
    @GetMapping("/review-requests")
    fun listReviewRequests(
        @RequestParam(defaultValue = "PENDING") status: ReviewStatus,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<OperatorPage<OperatorReview>> = ApiResponse.success(queryService.reviews(status.name, cursor, size))

    @Operation(
        operationId = "listTerms",
        summary = "낱말 목록 (FR-002, FR-014)",
        responses = [
            DocResponse(responseCode = "200", description = "낱말 목록"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아님 (NOT_FOUND)"),
        ],
    )
    @GetMapping("/terms")
    fun listTerms(
        @RequestParam(required = false) kind: TermKind?,
    ): ApiResponse<List<TermRow>> = ApiResponse.success(termService.list(kind))
}
