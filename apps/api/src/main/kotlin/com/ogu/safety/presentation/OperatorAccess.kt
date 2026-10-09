package com.ogu.safety.presentation

import com.ogu.member.AuthenticatedMember
import com.ogu.member.MemberApi
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 운영자 경로를 운영자에게만 연다(005 US4-AC6, research R10). 운영자가 아니면 `404 NOT_FOUND`라서 경로가 있다는 것도
 * 드러나지 않는다. 입력을 읽기 전에 막으므로 잘못된 입력에 400이 먼저 나가는 일도 없다. 역할은 요청마다 DB에서 읽는다.
 * 토큰에 넣으면 권한을 거둔 뒤에도 토큰이 살아 있는 동안 통한다.
 */
@Component
class OperatorInterceptor(
    private val memberApi: MemberApi,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val member = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedMember
        if (member == null || !memberApi.isOperator(member.memberId)) throw BusinessException(ErrorCode.NOT_FOUND)
        return true
    }
}

@Configuration(proxyBeanMethods = false)
class OperatorAccessConfig(
    private val interceptor: OperatorInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("$OPERATOR_PATH/**")
    }
}

internal const val OPERATOR_PATH = "/api/v1/operator"
