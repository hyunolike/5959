package com.ogu

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * 테스트 전용. spring-boot-starter-security가 클래스패스에 있으면 커스텀 SecurityFilterChain이 없는 한
 * 모든 요청에 인증을 요구하는 기본 체인이 적용되어 ContractTests가 /v3/api-docs 를 읽을 수 없다.
 * 운영 보안 설정(SecurityConfig)은 Batch 3(T015)에서 추가되며, 그때 /v3/api-docs 공개 여부가
 * 영구적으로 결정된다. 이 클래스는 그 전까지 계약 테스트만 통과시키는 최소 permit이다.
 */
@TestConfiguration(proxyBeanMethods = false)
class ContractTestSecurityConfig {
    @Bean
    fun contractTestSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }
        return http.build()
    }
}
