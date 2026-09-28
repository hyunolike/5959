package com.ogu.member.infrastructure.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment

/**
 * 운영(prod 프로필)에서 인증 비밀 값이 빠졌거나 저장소에 커밋된 개발용 값이면 기동을 막는다.
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
        check(properties.bffKey.isNotBlank()) {
            "prod 프로필에서는 ogu.auth.bff-key(OGU_BFF_KEY)를 설정해야 합니다."
        }
    }

    companion object {
        /** application-local.yml에 커밋된 값. ProdAuthSettingsCheckTest가 두 값이 같은지 확인한다. */
        const val LOCAL_DEV_JWT_SECRET = "local-dev-only-jwt-secret-do-not-use-in-production-0123456789"
    }
}
