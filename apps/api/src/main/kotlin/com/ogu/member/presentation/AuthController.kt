package com.ogu.member.presentation

import com.ogu.member.application.SignupService
import com.ogu.member.presentation.dto.AuthResultResponse
import com.ogu.member.presentation.dto.SignupRequest
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocResponse

/**
 * 가입, 로그인, 외부 계정 로그인, 세션 갱신, 로그아웃. 경로와 응답 코드는 specs/002-auth/contracts/openapi.yaml을 따른다.
 */
@Tag(name = "auth")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val signupService: SignupService,
) {
    @Operation(
        operationId = "signup",
        summary = "이메일 가입 (US1-AC1~AC3)",
        responses = [
            DocResponse(responseCode = "201", description = "가입과 동시에 로그인된다. member.onboarded는 false다."),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(
                responseCode = "409",
                description =
                    "이미 가입된 이메일(EMAIL_ALREADY_REGISTERED), " +
                        "또는 외부 계정 회원이 쓰는 이메일(EMAIL_REGISTERED_WITH_OTHER_METHOD)",
            ),
        ],
    )
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(
        @RequestBody request: SignupRequest,
    ): ApiResponse<AuthResultResponse> {
        val result = signupService.signup(request.email, request.password)
        return ApiResponse.success(AuthResultResponse.of(result.member, result.tokens, newMember = true))
    }
}
