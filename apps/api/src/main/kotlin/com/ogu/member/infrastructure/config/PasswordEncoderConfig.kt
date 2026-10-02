package com.ogu.member.infrastructure.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * 비밀번호 해시(research R7). `DelegatingPasswordEncoder` 기본값이라 bcrypt(강도 10)로 `{bcrypt}...`를 만든다.
 */
@Configuration(proxyBeanMethods = false)
class PasswordEncoderConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
}
