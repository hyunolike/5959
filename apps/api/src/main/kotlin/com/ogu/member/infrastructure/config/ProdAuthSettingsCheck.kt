package com.ogu.member.infrastructure.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment

/**
 * 운영(prod 프로필)에서 인증 비밀 값이 빠졌거나 저장소에 커밋된 개발용 값이면 기동을 막는다.
 * 외부 로그인 client id와 secret이 비었거나, 허용한 redirect URI가 https가 아니어도 막는다.
 * 커밋된 JWT 비밀키로는 누구나 access 토큰을 위조할 수 있고, BFF 키가 비면 로그인 실패 제한이 BFF 주소 하나로 묶인다.
 * e2e 프로필과 동시에 켜는 조합도 막는다 — e2e의 JWT_SECRET/OGU_BFF_KEY는 infra/compose.e2e.yaml에
 * 저장소째 커밋된 고정 값이라, prod에 같이 켜지면 누구나 access 토큰을 위조할 수 있다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdAuthSettingsCheck(
    properties: AuthProperties,
    environment: Environment,
) {
    init {
        check(!environment.activeProfiles.contains("e2e")) {
            "prod 프로필과 e2e 프로필을 동시에 켤 수 없습니다. e2e 프로필의 인증 비밀 값은 저장소에 커밋된 고정 값입니다."
        }
        check(properties.jwt.secret != LOCAL_DEV_JWT_SECRET) {
            "prod 프로필에서 ogu.auth.jwt.secret(JWT_SECRET)에 로컬 개발용 값을 쓸 수 없습니다."
        }
        check(properties.jwt.secret != E2E_JWT_SECRET) {
            "prod 프로필에서 ogu.auth.jwt.secret(JWT_SECRET)에 infra/compose.e2e.yaml의 고정 값을 쓸 수 없습니다."
        }
        check(properties.bffKey.isNotBlank()) {
            "prod 프로필에서는 ogu.auth.bff-key(OGU_BFF_KEY)를 설정해야 합니다."
        }
        check(properties.bffKey != LOCAL_DEV_BFF_KEY) {
            "prod 프로필에서 ogu.auth.bff-key(OGU_BFF_KEY)에 로컬 개발용 값을 쓸 수 없습니다."
        }
        checkOAuth(properties.oauth)
    }

    private fun checkOAuth(oauth: AuthProperties.OAuth) {
        val credentials =
            mapOf(
                "ogu.auth.oauth.kakao.client-id" to oauth.kakao.clientId,
                "ogu.auth.oauth.kakao.client-secret" to oauth.kakao.clientSecret,
                "ogu.auth.oauth.google.client-id" to oauth.google.clientId,
                "ogu.auth.oauth.google.client-secret" to oauth.google.clientSecret,
            )
        credentials.forEach { (name, value) ->
            check(value.isNotBlank()) { "prod 프로필에서는 $name 을 설정해야 합니다." }
        }
        val insecure = oauth.allowedRedirectUris.filterNot { it.startsWith("https://") }
        check(insecure.isEmpty()) {
            "prod 프로필에서 ogu.auth.oauth.allowed-redirect-uris는 https만 쓸 수 있습니다: $insecure"
        }
    }

    companion object {
        /** application-local.yml에 커밋된 값. ProdAuthSettingsCheckTest가 두 값이 같은지 확인한다. */
        const val LOCAL_DEV_JWT_SECRET = "local-dev-only-jwt-secret-do-not-use-in-production-0123456789"

        /** application-local.yml에 커밋된 값. ProdAuthSettingsCheckTest가 두 값이 같은지 확인한다. */
        const val LOCAL_DEV_BFF_KEY = "local-bff-key"

        /**
         * infra/compose.e2e.yaml에 커밋된 e2e 전용 고정 값. 누구나 저장소에서 읽을 수 있으므로
         * prod에서 쓰면 access 토큰을 위조할 수 있다. ProdAuthSettingsCheckTest가 두 값이 같은지 확인한다.
         */
        const val E2E_JWT_SECRET = "e2e-fixed-jwt-secret-for-playwright-full-tests-0123456789"
    }
}
