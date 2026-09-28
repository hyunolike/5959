package com.ogu.member.infrastructure.security

import com.ogu.shared.error.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 온보딩을 마치지 않은 회원(`onboarded=false` 토큰)이 허용 목록 밖의 경로를 부르면 `403 ONBOARDING_REQUIRED`로 막는다
 * (FR-007, research R9). 허용 목록은 대소문자와 끝 슬래시까지 정확히 일치해야 하고, API 접두사 밖의 경로도
 * 허용 목록이 아니면 막는다(대소문자를 바꾼 경로로 우회하지 못하게). [SessionCheckFilter] 뒤에서 돈다. 인증되지 않은 요청은 인가 단계가 401로 처리하므로 넘긴다.
 *
 * 필터 체인 안에서만 쓰도록 빈으로 등록하지 않는다. 빈으로 두면 서블릿 필터로도 한 번 더 등록된다.
 */
class OnboardingGuard(
    private val errorWriter: SecurityErrorWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val blocked =
            authentication is MemberAuthentication &&
                !authentication.member.onboarded &&
                !SecurityPaths.onboardingAllowedMatcher.matches(request)
        if (blocked) {
            errorWriter.write(response, ErrorCode.ONBOARDING_REQUIRED)
            return
        }
        filterChain.doFilter(request, response)
    }
}
