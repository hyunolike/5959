package com.ogu.safety.presentation

import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/** 계약의 `SupportResource`. */
data class SupportResourceResponse(
    val name: String,
    val phone: String,
    val hours: String,
    val description: String,
)

/** 도움 리소스, 신고, 재검토 요청(005). 온보딩 전 회원은 `OnboardingGuard`가 403으로 막는다. */
@Tag(name = "safety")
@RestController
@SecurityRequirement(name = "bearer")
class SafetyController(
    private val jdbcClient: JdbcClient,
) {
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
