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
            // 015: 비밀번호를 잊은 회원은 로그인할 수 없다. 켜져 있는지, 요청, 재설정 모두 토큰 없이 부른다
            "/api/v1/auth/password-reset",
            "/api/v1/auth/password-reset/confirm",
            "/actuator/health",
            // 004: Redis 상태만 따로 보는 헬스 그룹(research R5). 상세는 보이지 않고 상태만 준다
            "/actuator/health/realtime",
            // 004: 실시간 알림 스트림. 브라우저가 Bearer 없이 일회용 연결 표(ticket)로 바로 붙는다(research R3)
            "/api/v1/notifications/stream",
        )

    /**
     * 토큰 없이 `GET`으로만 부를 수 있는 경로. 011: 로그인하지 않은 방문자가 보는 최근 글. 닉네임과 회원 번호를 싣지 않는
     * 따로 둔 조회 하나만 연다. 회원의 피드(`/api/v1/feed`)와 글 상세, 댓글은 열지 않는다. 같은 경로에 쓰기가 생겨도
     * 함께 열리지 않게 메서드까지 본다.
     */
    val PUBLIC_GET = listOf("/api/v1/public/feed")

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
            // 013: 가입만 하고 그만두는 회원도 탈퇴할 수 있다(US1-AC9)
            HttpMethod.POST to "/api/v1/members/me/withdrawal",
        )

    fun publicMatcher(apiDocsEnabled: Boolean): RequestMatcher {
        val patterns = if (apiDocsEnabled) PUBLIC + API_DOCS else PUBLIC
        val builder = PathPatternRequestMatcher.withDefaults()
        val getOnly = PUBLIC_GET.map { builder.matcher(HttpMethod.GET, it) }
        return OrRequestMatcher(listOf(anyOf(patterns)) + getOnly)
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
