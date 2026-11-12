package com.ogu.member.infrastructure.oauth

import com.ogu.member.infrastructure.config.AuthProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
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
    ): GoogleClient = GoogleClient(properties.oauth.google, OAuthHttp.restClient(), clock)

    /**
     * 운영에서만 기동이 끝나면 구글 JWKS를 미리 받는다([GoogleClient.warmUp]). 기동 스레드를 붙잡지 않도록 가상
     * 스레드에서 돈다. 테스트가 컨텍스트를 띄울 때마다 구글을 부르지 않게 prod로 한정한다.
     */
    @Bean
    @Profile("prod")
    fun googleWarmUp(googleClient: GoogleClient): ApplicationListener<ApplicationReadyEvent> =
        ApplicationListener { Thread.ofVirtual().name("google-oauth-warm-up").start(googleClient::warmUp) }
}
