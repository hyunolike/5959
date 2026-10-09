package com.ogu.safety.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.post.ContentType
import com.ogu.safety.application.AssessmentReviewService
import com.ogu.safety.application.OperatorService
import com.ogu.safety.application.TermService
import com.ogu.safety.domain.TermKind
import com.ogu.safety.domain.TermRow
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 계약의 `OperatorNote`. */
data class OperatorNoteRequest(
    val note: String? = null,
)

enum class ReportDecisionType {
    RESOLVE,
    REJECT,
}

/** 계약의 `ReportDecision`. */
data class ReportDecisionRequest(
    val decision: ReportDecisionType? = null,
    val note: String? = null,
)

enum class ReviewDecisionType {
    KEEP,
    RESTORE,
}

/** 계약의 `ReviewDecision`. */
data class ReviewDecisionRequest(
    val decision: ReviewDecisionType? = null,
    val note: String? = null,
)

/** 계약의 `TermRequest`. */
data class TermRequest(
    val kind: TermKind? = null,
    val term: String? = null,
)

/**
 * 운영자의 처리(005 US4). [OperatorInterceptor]가 운영자가 아닌 요청을 404로 막는다. 처리는 모두 여러 번 불러도 결과가
 * 같다. 메모에는 본문을 옮겨 적지 않는다.
 */
@Tag(name = "operator")
@RestController
@RequestMapping(OPERATOR_PATH)
@SecurityRequirement(name = "bearer")
class OperatorController(
    private val operatorService: OperatorService,
    private val assessmentReviewService: AssessmentReviewService,
    private val termService: TermService,
) {
    @Operation(
        operationId = "markAssessmentReviewed",
        summary = "판정을 확인함으로 표시 (US4-AC1). 이미 확인했어도 204",
        responses = [
            DocResponse(responseCode = "204", description = "표시됨"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아니거나 없는 판정 (NOT_FOUND)"),
        ],
    )
    @PutMapping("/assessments/{assessmentId}/reviewed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markAssessmentReviewed(
        @PathVariable assessmentId: Long,
    ) {
        assessmentReviewService.markReviewed(assessmentId)
    }

    @Operation(
        operationId = "decideReport",
        summary = "신고 닫기 (US4-AC5). 이미 닫힌 신고면 그대로 204",
        responses = [
            DocResponse(responseCode = "204", description = "닫힘"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아니거나 없는 신고 (NOT_FOUND)"),
        ],
    )
    @PutMapping("/reports/{reportId}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun decideReport(
        member: AuthenticatedMember,
        @PathVariable reportId: Long,
        @RequestBody request: ReportDecisionRequest,
    ) {
        val decision = request.decision ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "결정을 주세요.")
        val resolve = decision == ReportDecisionType.RESOLVE
        operatorService.decideReport(member.memberId, reportId, resolve, request.note)
    }

    @Operation(
        operationId = "decideReview",
        summary = "재검토 닫기 (US4-AC3, AC9). RESTORE면 숨김을 풀고, KEEP이면 유지한다. 작성자에게 알림이 간다",
        responses = [
            DocResponse(responseCode = "204", description = "닫힘"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아니거나 없는 요청 (NOT_FOUND)"),
        ],
    )
    @PutMapping("/review-requests/{reviewId}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun decideReview(
        member: AuthenticatedMember,
        @PathVariable reviewId: Long,
        @RequestBody request: ReviewDecisionRequest,
    ) {
        val decision = request.decision ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "결정을 주세요.")
        val restore = decision == ReviewDecisionType.RESTORE
        operatorService.decideReview(member.memberId, reviewId, restore, request.note)
    }

    @Operation(
        operationId = "hideContent",
        summary = "대상 숨기기 (US4-AC4). 그 대상의 열린 신고를 모두 처리됨으로 닫는다. 이미 숨겨져 있어도 204",
        responses = [
            DocResponse(responseCode = "204", description = "숨김"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아니거나 없는 대상 (NOT_FOUND)"),
        ],
    )
    @PutMapping("/contents/{targetType}/{targetId}/hidden")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun hideContent(
        member: AuthenticatedMember,
        @PathVariable targetType: ContentType,
        @PathVariable targetId: Long,
        @RequestBody(required = false) request: OperatorNoteRequest?,
    ) {
        operatorService.hide(member.memberId, targetType, targetId, request?.note)
    }

    @Operation(
        operationId = "unhideContent",
        summary = "숨김 풀기 (US4-AC3). 작성자에게 알림이 간다. 숨겨져 있지 않아도 204",
        responses = [
            DocResponse(responseCode = "204", description = "다시 보임"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아니거나 없는 대상 (NOT_FOUND)"),
        ],
    )
    @DeleteMapping("/contents/{targetType}/{targetId}/hidden")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unhideContent(
        member: AuthenticatedMember,
        @PathVariable targetType: ContentType,
        @PathVariable targetId: Long,
    ) {
        operatorService.unhide(member.memberId, targetType, targetId)
    }

    @Operation(
        operationId = "addTerm",
        summary = "낱말 더하기. 모든 인스턴스에 퍼지기까지 최대 30초. 이미 있으면 그대로 200",
        responses = [
            DocResponse(responseCode = "200", description = "더해진 낱말"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아님 (NOT_FOUND)"),
        ],
    )
    @PostMapping("/terms")
    fun addTerm(
        member: AuthenticatedMember,
        @RequestBody request: TermRequest,
    ): ApiResponse<TermRow> {
        if (request.kind == null || request.term == null) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "낱말의 종류와 낱말을 주세요.")
        }
        return ApiResponse.success(termService.add(member.memberId, request.kind, request.term))
    }

    @Operation(
        operationId = "removeTerm",
        summary = "낱말 빼기. 없어도 204",
        responses = [
            DocResponse(responseCode = "204", description = "빠짐"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(responseCode = "404", description = "운영자가 아님 (NOT_FOUND)"),
        ],
    )
    @DeleteMapping("/terms/{termId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeTerm(
        member: AuthenticatedMember,
        @PathVariable termId: Long,
    ) {
        termService.remove(member.memberId, termId)
    }
}
