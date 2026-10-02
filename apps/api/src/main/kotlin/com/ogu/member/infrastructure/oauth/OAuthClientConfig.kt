package com.ogu.member.infrastructure.oauth

import com.ogu.member.infrastructure.config.AuthProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Clock

/** 실제 제공자 클라이언트. e2e 프로필에서는 [FakeOAuthClientConfig]가 같은 이름의 가짜 빈을 대신 등록한다. */
@Configuration(proxyBeanMethods = false)
@Profile("!e2e")
class OAuthClientConfig {
    @Bean
    fun kakaoClient(properties: AuthProperties): OAuthProviderClient {
        val settings = properties.oauth.kakao
        return KakaoClient(settings, OAuthHttp.restClient())
    }

    @Bean
    fun googleClient(
        properties: AuthProperties,
        clock: Clock,
    ): OAuthProviderClient = GoogleClient(properties.oauth.google, OAuthHttp.restClient(), clock)
}
