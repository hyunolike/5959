package com.ogu.member.infrastructure.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties::class, StreamTicketProperties::class)
class AuthConfig
