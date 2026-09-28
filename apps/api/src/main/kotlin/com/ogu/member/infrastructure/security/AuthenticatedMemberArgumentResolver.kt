package com.ogu.member.infrastructure.security

import com.ogu.member.AuthenticatedMember
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * 컨트롤러 메서드의 [AuthenticatedMember] 인자를 보안 컨텍스트의 인증 정보로 채운다.
 * 인증 없이 들어온 요청(공개 경로에 이 인자를 둔 경우)은 401로 거절한다.
 */
class AuthenticatedMemberArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean {
        val type = parameter.parameterType
        return type == AuthenticatedMember::class.java
    }

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): AuthenticatedMember {
        val authentication = SecurityContextHolder.getContext().authentication as? MemberAuthentication
        return authentication?.member ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
    }
}
