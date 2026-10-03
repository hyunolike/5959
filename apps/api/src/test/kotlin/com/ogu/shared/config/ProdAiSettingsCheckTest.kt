package com.ogu.shared.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ProdAiSettingsCheckTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(AiConfig::class.java, ProdAiSettingsCheck::class.java)

    @Test
    fun `prod 프로필에서 ogu ai api-key가 비어 있으면 기동하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.ai.api-key=")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("ogu.ai.api-key")
            }
    }

    @Test
    fun `prod 프로필에서 ogu ai api-key가 공백뿐이어도 기동하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.ai.api-key=   ")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("ogu.ai.api-key")
            }
    }

    @Test
    fun `prod 프로필에서 ogu ai api-key가 설정되면 기동한다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.ai.api-key=nvapi-test-key")
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `prod가 아닌 프로필에서는 ogu ai api-key가 비어 있어도 기동한다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("local") }
            .withPropertyValues("ogu.ai.api-key=")
            .run { context -> assertThat(context).hasNotFailed() }
    }
}
