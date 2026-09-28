package com.ogu.member.infrastructure.oauth

import com.ogu.member.domain.OAuthProvider
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * e2e 프로필에서 실제 제공자 대신 쓰는 가짜 제공자. 제공자를 부르지 않고 코드 문자열에서 사용자를 만든다.
 *
 * 코드 형식: `fake:<사용자 ID>:<이메일 또는 ->`
 * - `fake:kakao-1:user1@example.com` → 사용자 ID `kakao-1`, 인증된 이메일 `user1@example.com`
 * - `fake:kakao-2:-` → 사용자 ID `kakao-2`, 이메일 없음
 *
 * 같은 코드는 언제나 같은 사용자라, 같은 코드로 다시 로그인하면 기존 회원으로 로그인된다.
 * `denied`나 형식에 맞지 않는 코드는 제공자가 코드를 거절한 것처럼 `OAUTH_CODE_INVALID`를 던진다.
 * 아무 코드로나 로그인할 수 있으므로 운영에서 켜지면 안 된다([E2eProfileGuard]).
 */
class FakeOAuthProviderClient(
    override val provider: OAuthProvider,
) : OAuthProviderClient {
    override fun exchange(
        code: String,
        redirectUri: String,
        codeVerifier: String?,
    ): OAuthUserInfo {
        val match = CODE_PATTERN.matchEntire(code) ?: throw BusinessException(ErrorCode.OAUTH_CODE_INVALID)
        val (userId, emailPart) = match.destructured
        val email = emailPart.takeUnless { it == NO_EMAIL }
        return OAuthUserInfo(providerUserId = userId, email = email, emailVerified = email != null)
    }

    companion object {
        private val CODE_PATTERN = Regex("^fake:([^:]+):(.+)$")
        private const val NO_EMAIL = "-"
    }
}

/** e2e 프로필에서 카카오와 구글 모두 [FakeOAuthProviderClient]로 바꾼다. 빈 이름은 실제 클라이언트와 같다. */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
class FakeOAuthClientConfig {
    @Bean
    fun kakaoClient(): OAuthProviderClient = FakeOAuthProviderClient(OAuthProvider.KAKAO)

    @Bean
    fun googleClient(): OAuthProviderClient = FakeOAuthProviderClient(OAuthProvider.GOOGLE)
}
