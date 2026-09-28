package com.ogu.member.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.member.application.LoginService
import com.ogu.member.application.OAuthLoginService
import com.ogu.member.application.SessionService
import com.ogu.member.application.SignupService
import com.ogu.member.domain.SessionRevokeReason
import com.ogu.member.infrastructure.security.BffClientIpResolver
import com.ogu.member.presentation.dto.AuthResultResponse
import com.ogu.member.presentation.dto.LoginRequest
import com.ogu.member.presentation.dto.OAuthLoginRequest
import com.ogu.member.presentation.dto.SignupRequest
import com.ogu.shared.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
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
    private val loginService: LoginService,
    private val oauthLoginService: OAuthLoginService,
    private val sessionService: SessionService,
    private val clientIpResolver: BffClientIpResolver,
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

    @Operation(
        operationId = "login",
        summary = "이메일 로그인 (US2-AC1~AC4)",
        responses = [
            DocResponse(responseCode = "200", description = "로그인 성공"),
            DocResponse(responseCode = "400", description = "입력 검증 실패 (INVALID_REQUEST)"),
            DocResponse(
                responseCode = "401",
                description = "이메일 또는 비밀번호가 틀림. 어느 쪽인지 구분하지 않는다 (INVALID_CREDENTIALS)",
            ),
            DocResponse(
                responseCode = "429",
                description = "로그인 실패 제한에 걸림 (LOGIN_THROTTLED). error.retryAfterSeconds와 Retry-After 헤더가 있다",
            ),
        ],
    )
    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest,
    ): ApiResponse<AuthResultResponse> {
        val clientIp = clientIpResolver.resolve(httpRequest)
        val result = loginService.login(request.email, request.password, clientIp)
        return ApiResponse.success(AuthResultResponse.of(result.member, result.tokens, newMember = false))
    }

    @Operation(
        operationId = "oauthLogin",
        summary = "외부 계정 인가 코드 교환과 로그인 (US3-AC1~AC3)",
        responses = [
            DocResponse(responseCode = "200", description = "로그인 성공. 처음이면 newMember=true, member.onboarded=false"),
            DocResponse(
                responseCode = "400",
                description = "입력 검증 실패, 지원하지 않는 제공자, 허용 목록에 없는 redirectUri (INVALID_REQUEST)",
            ),
            DocResponse(responseCode = "401", description = "제공자가 코드를 거절함 (OAUTH_CODE_INVALID)"),
            DocResponse(
                responseCode = "409",
                description = "같은 이메일이 이메일 가입으로 이미 쓰임 (EMAIL_REGISTERED_WITH_OTHER_METHOD)",
            ),
            DocResponse(responseCode = "502", description = "제공자가 응답하지 않음 (OAUTH_PROVIDER_UNAVAILABLE)"),
        ],
    )
    @PostMapping("/oauth/{provider}")
    fun oauthLogin(
        @Parameter(schema = Schema(allowableValues = ["kakao", "google"]))
        @PathVariable provider: String,
        @RequestBody request: OAuthLoginRequest,
    ): ApiResponse<AuthResultResponse> {
        val result = oauthLoginService.login(provider, request.code, request.redirectUri, request.codeVerifier)
        return ApiResponse.success(AuthResultResponse.of(result.member, result.tokens, newMember = result.newMember))
    }

    @Operation(
        operationId = "logout",
        summary = "현재 세션 무효화 (US2-AC5)",
        security = [SecurityRequirement(name = "bearer")],
        responses = [
            DocResponse(
                responseCode = "204",
                description =
                    "세션이 무효가 되었다. 이미 무효이거나 만료된 세션의 토큰은 401이다" +
                        "(BFF는 결과와 관계없이 쿠키를 지운다)",
            ),
            DocResponse(
                responseCode = "401",
                description = "인증 없음 또는 세션 만료, 무효 (UNAUTHORIZED, SESSION_EXPIRED)",
            ),
        ],
    )
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(member: AuthenticatedMember) {
        // 이미 무효인 세션의 토큰은 SessionCheckFilter가 401 SESSION_EXPIRED로 먼저 막는다.
        sessionService.revoke(member.sessionId, SessionRevokeReason.LOGOUT)
    }
}
