package com.ogu.member.infrastructure.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource

class ProdAuthSettingsCheckTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(AuthConfig::class.java, ProdAuthSettingsCheck::class.java)
            .withPropertyValues(
                "ogu.auth.jwt.access-token-ttl=15m",
                "ogu.auth.session.idle-ttl=14d",
                "ogu.auth.session.absolute-ttl=30d",
                "ogu.auth.session.rotation-grace=30s",
            )

    @Test
    fun `prod 프로필에서 JWT 비밀키가 커밋된 로컬 개발용 값이면 기동하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.auth.jwt.secret=$LOCAL_DEV_SECRET", "ogu.auth.bff-key=prod-bff-key")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("ogu.auth.jwt.secret")
            }
    }

    @Test
    fun `prod 프로필에서 BFF 키가 비어 있으면 기동하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.auth.jwt.secret=$PROD_SECRET", "ogu.auth.bff-key=  ")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("ogu.auth.bff-key")
            }
    }

    @Test
    fun `prod 프로필에서 비밀키와 BFF 키가 제대로 설정되면 기동한다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.auth.jwt.secret=$PROD_SECRET", "ogu.auth.bff-key=prod-bff-key")
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `prod가 아닌 프로필에서는 로컬 개발용 값으로도 기동한다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("local") }
            .withPropertyValues("ogu.auth.jwt.secret=$LOCAL_DEV_SECRET", "ogu.auth.bff-key=")
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `검사하는 로컬 개발용 비밀키는 application-local yml의 값과 같다`() {
        val localYaml =
            YamlPropertiesFactoryBean()
                .apply { setResources(ClassPathResource("application-local.yml")) }
                .getObject()!!

        assertThat(ProdAuthSettingsCheck.LOCAL_DEV_JWT_SECRET).isEqualTo(localYaml.getProperty("ogu.auth.jwt.secret"))
    }

    companion object {
        private const val LOCAL_DEV_SECRET = "local-dev-only-jwt-secret-do-not-use-in-production-0123456789"
        private const val PROD_SECRET = "prod-like-jwt-secret-0123456789-0123456789-abcdef"
    }
}
