package com.ogu.notification.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.notification.application.NotificationQueryService
import com.ogu.notification.presentation.dto.UnreadCountResponse
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 알림 목록과 읽음(research R11). 실시간 스트림은 따로 둔다. */
@Tag(name = "notification")
@RestController
@SecurityRequirement(name = "bearer")
class NotificationController(
    private val queries: NotificationQueryService,
) {
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
