package com.ogu.member.infrastructure.oauth

import com.ogu.member.domain.OAuthProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * T054: 가짜 제공자는 아무 코드나 받아 로그인시킨다. 운영(prod)에서 e2e 프로필이 함께 켜지면 기동하지 않아야 한다.
 */
class E2eProfileGuardTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(E2eProfileGuard::class.java)

    @Test
    fun `e2e와 prod 프로필이 함께 켜지면 기동하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("e2e", "prod") }
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("e2e").hasMessageContaining("prod")
            }
    }

    @Test
    fun `e2e 프로필만 켜지면 기동한다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("e2e") }
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `e2e 프로필이 아니면 가드도 가짜 제공자도 등록되지 않는다`() {
        ApplicationContextRunner()
            .withUserConfiguration(E2eProfileGuard::class.java, FakeOAuthClientConfig::class.java)
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(E2eProfileGuard::class.java)
                assertThat(context).doesNotHaveBean(FakeOAuthProviderClient::class.java)
            }
    }

    @Test
    fun `e2e 프로필에서는 카카오와 구글 모두 가짜 제공자가 대신한다`() {
        ApplicationContextRunner()
            .withUserConfiguration(FakeOAuthClientConfig::class.java)
            .withInitializer { it.environment.setActiveProfiles("e2e") }
            .run { context ->
                val providers = context.getBeansOfType(OAuthProviderClient::class.java).values.map { it.provider }
                assertThat(providers).containsExactlyInAnyOrder(
                    OAuthProvider.KAKAO,
                    OAuthProvider.GOOGLE,
                )
                assertThat(context.getBeansOfType(OAuthProviderClient::class.java).values)
                    .allMatch { it is FakeOAuthProviderClient }
            }
    }
}
