package com.ogu.member.infrastructure.security

import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * 보안 규칙이 쓰는 경로 목록(tasks.md T015, T016).
 */
object SecurityPaths {
    const val API = "/api/v1/**"

    /** 토큰 없이 부를 수 있는 경로. 여기에 붙어 온 Bearer 토큰은 읽지 않는다. */
    val PUBLIC =
        listOf(
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/oauth/**",
            "/api/v1/auth/refresh",
            "/actuator/health",
            // springdoc이 켜져 있을 때만 존재한다. 운영(prod 프로필)은 springdoc.api-docs.enabled=false라 이 경로가 없다.
            "/v3/api-docs/**",
        )

    /** 온보딩 전(`onboarded=false`) 토큰으로도 부를 수 있는 인증 필요 경로(research R9). */
    val ONBOARDING_ALLOWED =
        listOf(
            "/api/v1/members/me",
            "/api/v1/members/nickname-availability",
            "/api/v1/members/me/onboarding",
            "/api/v1/auth/logout",
        )

    val publicMatcher: RequestMatcher = anyOf(PUBLIC)
    val onboardingAllowedMatcher: RequestMatcher = anyOf(ONBOARDING_ALLOWED)
    val apiMatcher: RequestMatcher = PathPatternRequestMatcher.withDefaults().matcher(API)

    private fun anyOf(patterns: List<String>): RequestMatcher {
        val builder = PathPatternRequestMatcher.withDefaults()
        return OrRequestMatcher(patterns.map { builder.matcher(it) })
    }
}
