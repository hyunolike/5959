package com.ogu.member.infrastructure.oauth

import com.ogu.member.infrastructure.config.AuthProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.time.Duration

/** 구글 JWKS 미리 받기는 운영(prod)에서만 등록한다. 테스트와 로컬은 기동할 때 구글을 부르지 않는다. */
class OAuthClientConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(OAuthClientConfig::class.java)
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(AuthProperties::class.java, { properties() })

    @Test
    fun `prod 프로필이면 구글 JWKS 미리 받기를 등록한다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasBean("googleWarmUp")
                assertThat(context).hasSingleBean(GoogleClient::class.java)
            }
    }

    @Test
    fun `prod가 아니면 미리 받기를 등록하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("local") }
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("googleWarmUp")
                assertThat(context.getBeansOfType(OAuthProviderClient::class.java)).hasSize(2)
            }
    }

    private fun properties() =
        AuthProperties(
            jwt = AuthProperties.Jwt(secret = "x".repeat(32), accessTokenTtl = Duration.ofMinutes(15)),
            session =
                AuthProperties.Session(
                    idleTtl = Duration.ofDays(14),
                    absoluteTtl = Duration.ofDays(30),
                    rotationGrace = Duration.ofSeconds(30),
                ),
        )
}
