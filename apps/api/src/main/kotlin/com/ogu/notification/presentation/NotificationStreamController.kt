package com.ogu.notification.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.member.MemberApi
import com.ogu.notification.presentation.dto.StreamTicketResponse
import com.ogu.notification.stream.SseHub
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/**
 * 실시간 알림 스트림(research R2~R4). 표는 BFF 전용 라우트가 Bearer로 받고, 브라우저는 그 표로 API 도메인의 스트림에 바로
 * 붙는다. 스트림은 공개 경로라 Bearer를 받지 않는다(access 토큰이 주소에 실리지 않는다, FR-006).
 */
@Tag(name = "notification")
@RestController
class NotificationStreamController(
    private val memberApi: MemberApi,
    private val hub: SseHub,
) {
    @Operation(
        operationId = "issueStreamTicket",
        summary = "실시간 연결용 일회용 티켓 발급 (FR-006). BFF 전용 라우트만 부른다",
        responses = [
            DocResponse(responseCode = "201", description = "발급됨. 30초 안에 한 번만 쓸 수 있다"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @SecurityRequirement(name = "bearer")
    @PostMapping("/api/v1/notifications/stream-tickets")
    @ResponseStatus(HttpStatus.CREATED)
    fun issueTicket(member: AuthenticatedMember): ApiResponse<StreamTicketResponse> =
        ApiResponse.success(StreamTicketResponse.from(memberApi.issueStreamTicket(member.memberId, member.sessionId)))

    /**
     * 표를 먼저 소비하고(없거나, 썼거나, 만료됐거나, 세션이 끝났으면 401), `lastEventId` 뒤의 알림을 번호 순서로 다시 보낸 뒤
     * 실시간으로 넘어간다. 표준 재연결 헤더 `Last-Event-ID`가 쿼리보다 우선한다.
     */
    @Operation(
        operationId = "streamNotifications",
        summary = "실시간 알림 스트림 (US1-AC1~AC8, FR-004~FR-006)",
        responses = [
            DocResponse(responseCode = "200", description = "스트림 시작"),
            DocResponse(responseCode = "400", description = "lastEventId 형식 오류"),
            DocResponse(responseCode = "401", description = "티켓이 유효하지 않음 (STREAM_TICKET_INVALID)"),
        ],
    )
    @SecurityRequirements
    @GetMapping("/api/v1/notifications/stream")
    fun stream(
        @Parameter(description = "issueStreamTicket이 준 일회용 티켓")
        @RequestParam(required = false) ticket: String?,
        @Parameter(description = "마지막으로 받은 이벤트 id(seq)")
        @RequestParam(required = false) lastEventId: Long?,
        @Parameter(description = "표준 재연결 헤더. 쿼리와 함께 오면 헤더를 쓴다")
        @RequestHeader(name = LAST_EVENT_ID, required = false) lastEventIdHeader: String?,
    ): ResponseEntity<SseEmitter> {
        val startAfter = resolveLastEventId(lastEventIdHeader, lastEventId)
        val memberId =
            ticket?.let(memberApi::consumeStreamTicket)
                ?: throw BusinessException(ErrorCode.STREAM_TICKET_INVALID)
        return ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(X_ACCEL_BUFFERING, "no")
            .body(hub.open(memberId, startAfter))
    }

    /**
     * 이 컨트롤러의 오류는 JSON으로 쓴다. `EventSource`는 `Accept: text/event-stream`으로 오므로 내용 협상에 맡기면 오류
     * 봉투를 쓸 변환기를 찾지 못한다. 형식을 정해 두면 협상을 건너뛴다.
     */
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ApiResponse<Unit>> =
        ResponseEntity
            .status(e.errorCode.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ApiResponse.error(e.errorCode, e.message))

    /**
     * 브라우저가 스트림을 닫으면 컨테이너가 오류로 알려 온다. 응답은 이미 끝났고 허브는 쓰기 실패나 오류 콜백으로 연결을
     * 지우므로, 여기서는 아무것도 쓰지 않는다(전역 처리기가 서버 오류로 남기지 않게 한다).
     */
    @ExceptionHandler(AsyncRequestNotUsableException::class)
    fun handleDisconnected(e: AsyncRequestNotUsableException) {
        log.debug("실시간 알림 스트림의 클라이언트가 끊겼습니다: {}", e.message)
    }

    /**
     * 그 밖의 오류(예: 표를 소비하다 난 DB 오류)도 JSON으로 쓴다. 전역 처리기에 맡기면 `Accept: text/event-stream` 때문에
     * 오류 봉투를 쓰지 못한다. Spring MVC가 상태를 정해 둔 예외는 그 상태를 쓴다.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ApiResponse<Unit>> {
        val status = (e as? ErrorResponse)?.statusCode ?: HttpStatus.INTERNAL_SERVER_ERROR
        val code = if (status.is4xxClientError) ErrorCode.INVALID_REQUEST else ErrorCode.INTERNAL_ERROR
        if (status.is5xxServerError) log.error("실시간 알림 스트림 요청을 처리하지 못했습니다", e)
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error(code))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(): ResponseEntity<ApiResponse<Unit>> {
        val invalid = BusinessException(ErrorCode.INVALID_REQUEST)
        return handleBusinessException(invalid)
    }

    private fun resolveLastEventId(
        header: String?,
        query: Long?,
    ): Long? {
        val value =
            if (header.isNullOrBlank()) {
                query
            } else {
                header.trim().toLongOrNull() ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
            }
        if (value != null && value < 0) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return value
    }

    private companion object {
        val log = LoggerFactory.getLogger(NotificationStreamController::class.java)
        const val LAST_EVENT_ID = "Last-Event-ID"
        const val X_ACCEL_BUFFERING = "X-Accel-Buffering"
    }
}
