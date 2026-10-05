package com.ogu.member.infrastructure.security

import org.springframework.http.HttpMethod
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
            // 004: Redis 상태만 따로 보는 헬스 그룹(research R5). 상세는 보이지 않고 상태만 준다
            "/actuator/health/realtime",
        )

    /** springdoc이 켜져 있을 때(`springdoc.api-docs.enabled`, 기본 true)만 공개한다. 운영은 이 설정이 false다. */
    const val API_DOCS = "/v3/api-docs/**"

    /**
     * 온보딩 전(`onboarded=false`) 토큰으로도 부를 수 있는 인증 필요 경로(002 research R9). 메서드가 null이면 모든 메서드다.
     * 내 프로필(`/api/v1/members/me`)은 `GET`만 연다. 004에서 같은 경로에 `PATCH`(프로필 수정)가 생겨, 경로만 보면
     * 온보딩을 건너뛰고 닉네임과 직군을 저장하는 길이 된다(004 research R13).
     */
    val ONBOARDING_ALLOWED: List<Pair<HttpMethod?, String>> =
        listOf(
            HttpMethod.GET to "/api/v1/members/me",
            null to "/api/v1/members/nickname-availability",
            null to "/api/v1/members/me/onboarding",
            null to "/api/v1/auth/logout",
        )

    fun publicMatcher(apiDocsEnabled: Boolean): RequestMatcher {
        val patterns = if (apiDocsEnabled) PUBLIC + API_DOCS else PUBLIC
        return anyOf(patterns)
    }

    val onboardingAllowedMatcher: RequestMatcher =
        PathPatternRequestMatcher.withDefaults().let { builder ->
            OrRequestMatcher(ONBOARDING_ALLOWED.map { (method, path) -> builder.matcher(method, path) })
        }
    val apiMatcher: RequestMatcher = PathPatternRequestMatcher.withDefaults().matcher(API)

    private fun anyOf(patterns: List<String>): RequestMatcher {
        val builder = PathPatternRequestMatcher.withDefaults()
        return OrRequestMatcher(patterns.map { builder.matcher(it) })
    }
}
