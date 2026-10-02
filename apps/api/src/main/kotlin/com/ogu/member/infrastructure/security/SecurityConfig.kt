package com.ogu.member.infrastructure.security

import com.ogu.member.AuthenticatedMember
import com.ogu.member.application.SessionService
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.shared.error.ErrorCode
import jakarta.servlet.DispatcherType
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock

/**
 * API 보안 설정. 보안 코드는 `member` 모듈 안에 둔다(`shared`가 `member`에 의존하지 않도록, plan의 Constitution Check I).
 *
 * 요청 처리 순서: Bearer JWT 검증(서명, `iss`, `exp`) → [SessionCheckFilter](세션 유효성) → [OnboardingGuard] → 인가 규칙.
 * API는 브라우저가 직접 부르지 않고 BFF만 부르므로(ADR-0002) 세션 쿠키와 CSRF 토큰을 쓰지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig(
    @param:Value("\${springdoc.api-docs.enabled:true}") private val apiDocsEnabled: Boolean,
) : WebMvcConfigurer {
    private val publicMatcher = SecurityPaths.publicMatcher(apiDocsEnabled)

    init {
        // 컨트롤러의 AuthenticatedMember 인자는 요청 파라미터가 아니므로 API 문서에서 뺀다.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthenticatedMember::class.java)
    }

    @Bean
    fun jwtDecoder(
        properties: AuthProperties,
        clock: Clock,
    ): JwtDecoder = JwtIssuer.decoder(properties.jwt.secret, clock)

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        sessionService: SessionService,
        errorWriter: SecurityErrorWriter,
    ): SecurityFilterChain {
        val entryPoint =
            AuthenticationEntryPoint { _, response, _ ->
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                errorWriter.write(response, ErrorCode.UNAUTHORIZED)
            }
        val accessDeniedHandler =
            AccessDeniedHandler { _, response, _ -> errorWriter.write(response, ErrorCode.FORBIDDEN) }
        val sessionCheckFilter = SessionCheckFilter(sessionService, errorWriter)

        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(publicMatcher)
                    .permitAll()
                    .requestMatchers(SecurityPaths.apiMatcher)
                    .authenticated()
                    .anyRequest()
                    .denyAll()
            }.oauth2ResourceServer { resourceServer ->
                resourceServer
                    .bearerTokenResolver(publicPathIgnoringResolver())
                    .jwt { jwt ->
                        jwt
                            .decoder(jwtDecoder)
                            .jwtAuthenticationConverter(MemberJwtAuthenticationConverter())
                    }.authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            }.exceptionHandling { exceptions ->
                exceptions
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            }.addFilterAfter(sessionCheckFilter, BearerTokenAuthenticationFilter::class.java)
            .addFilterAfter(OnboardingGuard(errorWriter), SessionCheckFilter::class.java)
        return http.build()
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(AuthenticatedMemberArgumentResolver())
    }

    /**
     * 공개 경로에서는 Authorization 헤더를 읽지 않는다. 만료되거나 로그아웃된 access 토큰이 붙어 와도
     * 로그인, 가입, refresh가 401로 막히지 않게 하기 위해서다.
     */
    private fun publicPathIgnoringResolver(): BearerTokenResolver {
        val delegate = DefaultBearerTokenResolver()
        return BearerTokenResolver { request ->
            if (publicMatcher.matches(request)) null else delegate.resolve(request)
        }
    }
}
