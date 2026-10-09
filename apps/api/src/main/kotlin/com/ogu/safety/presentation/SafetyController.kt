package com.ogu.safety.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.post.ContentType
import com.ogu.safety.application.ReportService
import com.ogu.safety.application.ReviewRequestService
import com.ogu.safety.domain.ReportReason
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 계약의 `SupportResource`. */
data class SupportResourceResponse(
    val name: String,
    val phone: String,
    val hours: String,
    val description: String,
)

/** 계약의 `ReportRequest`. 필드가 빠지면 null로 받아 400으로 거절한다. */
data class ReportRequest(
    val targetType: ContentType? = null,
    val targetId: Long? = null,
    val reason: ReportReason? = null,
    val detail: String? = null,
) {
    /** 대상의 종류, 대상, 사유. 하나라도 빠졌으면 400 INVALID_REQUEST. */
    fun required(): Triple<ContentType, Long, ReportReason> {
        if (targetType == null || targetId == null || reason == null) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "신고할 대상과 사유를 주세요.")
        }
        return Triple(targetType, targetId, reason)
    }
}

/** 계약의 `ReviewRequestBody`. */
data class ReviewRequestBody(
    val targetType: ContentType? = null,
    val targetId: Long? = null,
)

/** 도움 리소스, 신고, 재검토 요청(005). 온보딩 전 회원은 `OnboardingGuard`가 403으로 막는다. */
@Tag(name = "safety")
@RestController
@SecurityRequirement(name = "bearer")
class SafetyController(
    private val jdbcClient: JdbcClient,
    private val reportService: ReportService,
    private val reviewRequestService: ReviewRequestService,
) {
    @Operation(
        operationId = "reportContent",
        summary = "글이나 댓글 신고 (US3-AC1~AC5)",
        responses = [
            DocResponse(responseCode = "204", description = "접수됨. 신고 수나 처리 상태는 돌려주지 않는다"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(
                responseCode = "403",
                description = "온보딩 전(ONBOARDING_REQUIRED) 또는 자기 글이나 댓글(CANNOT_REPORT_OWN_CONTENT)",
            ),
            DocResponse(responseCode = "404", description = "없거나, 지웠거나, 숨긴 대상 (POST_NOT_FOUND, COMMENT_NOT_FOUND)"),
            DocResponse(responseCode = "409", description = "이미 신고한 대상 (ALREADY_REPORTED)"),
            DocResponse(responseCode = "429", description = "한 시간 20건 초과 (REPORT_RATE_LIMITED)"),
        ],
    )
    @PostMapping("/api/v1/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reportContent(
        member: AuthenticatedMember,
        @RequestBody request: ReportRequest,
    ) {
        val (type, targetId, reason) = request.required()
        reportService.report(member.memberId, type, targetId, reason, request.detail)
    }

    @Operation(
        operationId = "requestReview",
        summary = "숨겨진 내 글이나 댓글의 재검토 요청 (US4-AC8). 대상마다 한 번",
        responses = [
            DocResponse(responseCode = "204", description = "접수됨"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(
                responseCode = "404",
                description = "내 것이 아니거나, 숨겨지지 않았거나, 없는 대상 (POST_NOT_FOUND, COMMENT_NOT_FOUND)",
            ),
            DocResponse(responseCode = "409", description = "이미 요청함 (REVIEW_ALREADY_REQUESTED)"),
        ],
    )
    @PostMapping("/api/v1/review-requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun requestReview(
        member: AuthenticatedMember,
        @RequestBody request: ReviewRequestBody,
    ) {
        if (request.targetType == null || request.targetId == null) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "재검토할 대상을 주세요.")
        }
        reviewRequestService.request(member.memberId, request.targetType, request.targetId)
    }

    @Operation(
        operationId = "getSupportResources",
        summary = "도움 리소스 목록 (US1-AC1, AC3, AC4). 보이는 순서대로",
        responses = [
            DocResponse(responseCode = "200", description = "도움 리소스"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @GetMapping("/api/v1/safety/support-resources")
    fun getSupportResources(): ApiResponse<List<SupportResourceResponse>> =
        ApiResponse.success(
            jdbcClient
                .sql("select name, phone, hours, description from support_resource where active order by display_order")
                .query { rs, _ ->
                    SupportResourceResponse(
                        name = rs.getString("name"),
                        phone = rs.getString("phone"),
                        hours = rs.getString("hours"),
                        description = rs.getString("description"),
                    )
                }.list(),
        )
}
