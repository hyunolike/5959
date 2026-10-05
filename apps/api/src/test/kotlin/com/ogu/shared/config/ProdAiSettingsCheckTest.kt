package com.ogu.shared.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.net.URI

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
    fun `ogu ai base-url이 빈 주소면 검사가 기동을 막는다`() {
        val properties = AiProperties(baseUrl = URI.create(""), apiKey = "nvapi-test-key")

        assertThatThrownBy { ProdAiSettingsCheck(properties) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ogu.ai.base-url")
    }

    /** 빈 문자열이나 공백은 URI로 바인딩되지 않아 기본값이 남는다. compose.prod.yaml의 기본값과 같은 주소다. */
    @Test
    fun `prod 프로필에서 ogu ai base-url이 빈 문자열이면 기본 주소로 기동한다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.ai.api-key=nvapi-test-key", "ogu.ai.base-url=")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(AiProperties::class.java).baseUrl.toString())
                    .isEqualTo("https://integrate.api.nvidia.com/v1")
            }
    }

    @Test
    fun `prod 프로필에서 ogu ai model이 비어 있으면 기동하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.ai.api-key=nvapi-test-key", "ogu.ai.model=  ")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("ogu.ai.model")
            }
    }

    @Test
    fun `prod가 아닌 프로필에서는 ogu ai api-key가 비어 있어도 기동한다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("local") }
            .withPropertyValues("ogu.ai.api-key=")
            .run { context -> assertThat(context).hasNotFailed() }
    }
}
