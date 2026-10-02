package com.ogu.member.infrastructure.oauth

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment

/**
 * e2e 프로필에서는 [FakeOAuthProviderClient]가 아무 코드로나 로그인시킨다. prod 프로필과 함께 켜지면 기동을 막는다.
 * prod 쪽에서도 `ProdAuthSettingsCheck`가 같은 조합을 막는다(e2e의 고정 JWT 비밀키 때문). 어느 한쪽 설정이 빠져도
 * 막히도록 가짜 제공자 옆에 따로 둔다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
class E2eProfileGuard(
    environment: Environment,
) {
    init {
        check(!environment.activeProfiles.contains("prod")) {
            "e2e 프로필과 prod 프로필을 동시에 켤 수 없습니다. e2e 프로필은 가짜 외부 로그인 제공자를 씁니다."
        }
    }
}
