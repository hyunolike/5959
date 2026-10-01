package com.ogu.member.infrastructure.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource

class ProdAuthSettingsCheckTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(AuthConfig::class.java, ProdAuthSettingsCheck::class.java)
            .withPropertyValues(
                "ogu.auth.jwt.access-token-ttl=15m",
                "ogu.auth.session.idle-ttl=14d",
                "ogu.auth.session.absolute-ttl=30d",
                "ogu.auth.session.rotation-grace=30s",
                // 운영에 맞는 외부 로그인 설정. 각 테스트가 필요한 값만 덮어쓴다.
                "ogu.auth.oauth.allowed-redirect-uris=$KAKAO_CALLBACK,$GOOGLE_CALLBACK",
                "ogu.auth.oauth.kakao.client-id=kakao-id",
                "ogu.auth.oauth.kakao.client-secret=kakao-secret",
                "ogu.auth.oauth.google.client-id=google-id",
                "ogu.auth.oauth.google.client-secret=google-secret",
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
    fun `prod 프로필에서 JWT 비밀키가 e2e 프로필의 고정 값이면 기동하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.auth.jwt.secret=$E2E_SECRET", "ogu.auth.bff-key=prod-bff-key")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("ogu.auth.jwt.secret")
            }
    }

    @Test
    fun `prod 프로필에서 BFF 키가 로컬 개발용 값(local-bff-key)이면 기동하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.auth.jwt.secret=$PROD_SECRET", "ogu.auth.bff-key=local-bff-key")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("ogu.auth.bff-key")
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

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ogu.auth.oauth.kakao.client-id",
            "ogu.auth.oauth.kakao.client-secret",
            "ogu.auth.oauth.google.client-id",
            "ogu.auth.oauth.google.client-secret",
        ],
    )
    fun `prod 프로필에서 외부 로그인 client id나 secret이 비어 있으면 기동하지 않는다`(property: String) {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues("ogu.auth.jwt.secret=$PROD_SECRET", "ogu.auth.bff-key=prod-bff-key", "$property= ")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining(property)
            }
    }

    @Test
    fun `prod 프로필에서 https가 아닌 redirect URI가 허용 목록에 있으면 기동하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod") }
            .withPropertyValues(
                "ogu.auth.jwt.secret=$PROD_SECRET",
                "ogu.auth.bff-key=prod-bff-key",
                "ogu.auth.oauth.allowed-redirect-uris=$KAKAO_CALLBACK,$INSECURE_GOOGLE_CALLBACK",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .rootCause()
                    .hasMessageContaining("ogu.auth.oauth.allowed-redirect-uris")
                    .hasMessageContaining("http://ogu.example.com")
            }
    }

    @Test
    fun `prod와 e2e 프로필을 함께 켜면 기동하지 않는다`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod", "e2e") }
            .withPropertyValues("ogu.auth.jwt.secret=$PROD_SECRET", "ogu.auth.bff-key=prod-bff-key")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("e2e")
            }
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

    @Test
    fun `검사하는 로컬 개발용 BFF 키는 application-local yml의 값과 같다`() {
        val localYaml =
            YamlPropertiesFactoryBean()
                .apply { setResources(ClassPathResource("application-local.yml")) }
                .getObject()!!

        assertThat(ProdAuthSettingsCheck.LOCAL_DEV_BFF_KEY).isEqualTo(localYaml.getProperty("ogu.auth.bff-key"))
    }

    @Test
    fun `검사하는 e2e 고정 JWT 비밀키는 infra compose e2e yaml의 값과 같다`() {
        // infra/compose.e2e.yaml은 이 모듈(apps/api) 밖, 저장소 루트 아래에 있어
        // classpath 리소스가 아니다 — Gradle test 작업 디렉터리(apps/api)를 기준으로 읽는다.
        val composeYaml =
            YamlPropertiesFactoryBean()
                .apply { setResources(FileSystemResource("../../infra/compose.e2e.yaml")) }
                .getObject()!!

        assertThat(ProdAuthSettingsCheck.E2E_JWT_SECRET)
            .isEqualTo(composeYaml.getProperty("services.api.environment.JWT_SECRET"))
    }

    companion object {
        private const val LOCAL_DEV_SECRET = "local-dev-only-jwt-secret-do-not-use-in-production-0123456789"
        private const val KAKAO_CALLBACK = "https://ogu.example.com/api/auth/oauth/kakao/callback"
        private const val GOOGLE_CALLBACK = "https://ogu.example.com/api/auth/oauth/google/callback"
        private const val INSECURE_GOOGLE_CALLBACK = "http://ogu.example.com/api/auth/oauth/google/callback"
        private const val PROD_SECRET = "prod-like-jwt-secret-0123456789-0123456789-abcdef"
        private const val E2E_SECRET = "e2e-fixed-jwt-secret-for-playwright-full-tests-0123456789"
    }
}
