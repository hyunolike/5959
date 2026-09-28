package com.ogu.member.infrastructure.security

import com.ogu.member.application.SessionService
import com.ogu.shared.error.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT 서명 검증 뒤에 `sid` 세션이 살아 있는지 확인한다(research R1, SC-004). 로그아웃하거나 만료된 세션의 토큰은
 * JWT가 아직 유효해도 `401 SESSION_EXPIRED`로 막는다. 요청마다 세션 PK 조회 한 번이다.
 *
 * 필터 체인 안에서만 쓰도록 빈으로 등록하지 않는다. 빈으로 두면 서블릿 필터로도 한 번 더 등록된다.
 */
class SessionCheckFilter(
    private val sessionService: SessionService,
    private val errorWriter: SecurityErrorWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication is MemberAuthentication && !sessionService.isActive(authentication.member.sessionId)) {
            SecurityContextHolder.clearContext()
            errorWriter.write(response, ErrorCode.SESSION_EXPIRED)
            return
        }
        filterChain.doFilter(request, response)
    }
}
