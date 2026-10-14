package com.ogu.member.presentation

import com.ogu.member.application.PasswordResetConfirmation
import com.ogu.member.application.PasswordResetService
import com.ogu.member.infrastructure.security.BffClientIpResolver
import com.ogu.member.presentation.dto.PasswordResetAvailabilityResponse
import com.ogu.member.presentation.dto.PasswordResetConfirmRequest
import com.ogu.member.presentation.dto.PasswordResetRequest
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/**
 * 비밀번호 재설정(015). 세 경로 모두 로그인 없이 부른다(`SecurityPaths.PUBLIC`). 요청의 응답은 가입 여부와 상관없이
 * 같고, 재설정은 그 회원을 로그인시키지 않는다. 응답에 토큰이 없어 BFF가 쿠키를 다루지 않는다.
 */
@Tag(name = "auth")
@RestController
@RequestMapping("/api/v1/auth/password-reset")
class PasswordResetController(
    private val service: PasswordResetService,
    private val confirmation: PasswordResetConfirmation,
    private val clientIpResolver: BffClientIpResolver,
) {
    @Operation(
        operationId = "getPasswordResetAvailability",
        summary = "비밀번호 재설정을 쓸 수 있는지 (015 US4-AC1, AC3)",
        responses = [DocResponse(responseCode = "200", description = "메일 발송이 켜져 있으면 available이 true다")],
    )
    @GetMapping
    fun availability(): ApiResponse<PasswordResetAvailabilityResponse> =
        ApiResponse.success(PasswordResetAvailabilityResponse(service.available()))

    @Operation(
        operationId = "requestPasswordReset",
        summary = "비밀번호 재설정 메일 요청 (015 US1, US3)",
        responses = [
            DocResponse(responseCode = "204", description = "받았다. 메일이 갔는지는 알려 주지 않는다"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(responseCode = "429", description = "같은 곳에서 너무 자주 요청했다 (PASSWORD_RESET_THROTTLED)"),
            DocResponse(responseCode = "503", description = "메일 발송이 꺼져 있다 (PASSWORD_RESET_UNAVAILABLE)"),
        ],
    )
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun request(
        @RequestBody request: PasswordResetRequest,
        httpRequest: HttpServletRequest,
    ) {
        val email = request.email ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "이메일을 입력해 주세요.")
        service.request(email, clientIpResolver.resolve(httpRequest))
    }

    @Operation(
        operationId = "confirmPasswordReset",
        summary = "링크의 값으로 새 비밀번호를 정한다 (015 US2)",
        responses = [
            DocResponse(responseCode = "204", description = "비밀번호를 바꿨다"),
            DocResponse(
                responseCode = "400",
                description =
                    "비밀번호가 규칙에 맞지 않는다 (INVALID_REQUEST). " +
                        "또는 링크를 쓸 수 없다 (PASSWORD_RESET_LINK_INVALID)",
            ),
        ],
    )
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirm(
        @RequestBody request: PasswordResetConfirmRequest,
        httpRequest: HttpServletRequest,
    ) {
        val password = request.password ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "새 비밀번호를 입력해 주세요.")
        confirmation.confirm(request.token.orEmpty(), password, clientIpResolver.resolve(httpRequest))
    }
}
