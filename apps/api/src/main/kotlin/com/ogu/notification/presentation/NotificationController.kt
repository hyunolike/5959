package com.ogu.notification.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.notification.application.NotificationQueryService
import com.ogu.notification.application.NotificationReadService
import com.ogu.notification.presentation.dto.NotificationPageResponse
import com.ogu.notification.presentation.dto.ReadAllRequest
import com.ogu.notification.presentation.dto.ReadAllResponse
import com.ogu.notification.presentation.dto.UnreadCountResponse
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 알림 목록과 읽음(research R11). 실시간 스트림은 따로 둔다. */
@Tag(name = "notification")
@RestController
@SecurityRequirement(name = "bearer")
class NotificationController(
    private val queries: NotificationQueryService,
    private val reads: NotificationReadService,
) {
    @Operation(
        operationId = "getNotifications",
        summary = "알림 목록 (US2-AC1, AC2). 최신순 20개씩, 보관 기간(90일) 안의 것만",
        responses = [
            DocResponse(responseCode = "200", description = "알림 페이지"),
            DocResponse(responseCode = "400", description = "size가 1~50 밖이거나 커서가 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @GetMapping("/api/v1/notifications")
    fun getNotifications(
        member: AuthenticatedMember,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<NotificationPageResponse> =
        ApiResponse.success(NotificationPageResponse.from(queries.page(member.memberId, cursor, size)))

    @Operation(
        operationId = "markNotificationRead",
        summary = "알림 하나 읽음 (US2-AC3, AC6). 이미 읽었어도 204",
        responses = [
            DocResponse(responseCode = "204", description = "읽음으로 바뀜"),
            DocResponse(responseCode = "400", description = "알림 ID 형식이 틀림 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
            DocResponse(
                responseCode = "404",
                description = "없거나 다른 회원의 알림이거나 보관 기간이 지남 (NOTIFICATION_NOT_FOUND)",
            ),
        ],
    )
    @PutMapping("/api/v1/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markNotificationRead(
        member: AuthenticatedMember,
        @PathVariable notificationId: Long,
    ) {
        reads.markOne(member.memberId, notificationId)
    }

    @Operation(
        operationId = "markAllNotificationsRead",
        summary = "모두 읽음 (US2-AC4). upToSeq 이하만 바꾼다",
        responses = [
            DocResponse(responseCode = "200", description = "처리됨"),
            DocResponse(responseCode = "400", description = "upToSeq가 없거나 음수 (INVALID_REQUEST)"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @PostMapping("/api/v1/notifications/read-all")
    fun markAllNotificationsRead(
        member: AuthenticatedMember,
        @RequestBody request: ReadAllRequest,
    ): ApiResponse<ReadAllResponse> {
        val result = reads.markAll(member.memberId, request.upToSeq)
        return ApiResponse.success(ReadAllResponse.from(result))
    }

    @Operation(
        operationId = "getUnreadNotificationCount",
        summary = "안 읽은 알림 수 (FR-009)",
        responses = [
            DocResponse(responseCode = "200", description = "안 읽은 수와 마지막 번호"),
            DocResponse(responseCode = "401", description = "인증 없음 또는 세션 만료"),
            DocResponse(responseCode = "403", description = "온보딩 전 (ONBOARDING_REQUIRED)"),
        ],
    )
    @GetMapping("/api/v1/notifications/unread-count")
    fun unreadCount(member: AuthenticatedMember): ApiResponse<UnreadCountResponse> =
        ApiResponse.success(UnreadCountResponse.from(queries.unreadCount(member.memberId)))
}
