package com.ogu.member.application

import com.ogu.member.domain.OAuthProvider
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.member.infrastructure.oauth.OAuthProviderClient
import com.ogu.member.infrastructure.persistence.UniqueConstraints
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * 외부 계정 로그인(US3-AC1~AC3, FR-005, FR-006, research R4, R5).
 *
 * 순서: 제공자와 `redirectUri` 확인 → 제공자와 코드 교환 → [OAuthMemberRegistrar]가 한 트랜잭션에서 로그인 또는 가입.
 * 코드 교환은 수 초 걸릴 수 있어 트랜잭션 밖에서 한다.
 *
 * 같은 외부 계정의 첫 로그인이 동시에 오면 뒤 요청의 연결 저장이 `oauth_identity_provider_user_key`에 걸린다.
 * 그때는 새 트랜잭션에서 한 번 더 처리해 앞 요청이 만든 연결로 로그인시킨다. 다른 제약 위반은 그대로 던진다.
 */
@Service
class OAuthLoginService(
    private val clients: List<OAuthProviderClient>,
    private val registrar: OAuthMemberRegistrar,
    private val properties: AuthProperties,
) {
    fun login(
        providerPath: String,
        code: String,
        redirectUri: String,
        codeVerifier: String?,
    ): OAuthLoginResult {
        val provider = validate(providerPath, code, redirectUri)
        val user = clientFor(provider).exchange(code, redirectUri, codeVerifier)
        return try {
            registrar.loginOrRegister(provider, user)
        } catch (e: DataIntegrityViolationException) {
            if (!UniqueConstraints.isViolated(e, UniqueConstraints.OAUTH_IDENTITY_PROVIDER_USER)) throw e
            registrar.loginOrRegister(provider, user)
        }
    }

    /** 제공자를 부르기 전에 확인한다. `redirectUri`는 허용 목록의 값 하나와 정확히 같아야 한다. */
    private fun validate(
        providerPath: String,
        code: String,
        redirectUri: String,
    ): OAuthProvider {
        val provider = OAuthProvider.fromPath(providerPath) ?: invalid("지원하지 않는 외부 계정 제공자입니다.")
        if (code.isBlank()) invalid("인가 코드가 없습니다.")
        if (redirectUri !in properties.oauth.allowedRedirectUris) invalid("허용되지 않은 redirectUri입니다.")
        return provider
    }

    private fun invalid(message: String): Nothing = throw BusinessException(ErrorCode.INVALID_REQUEST, message)

    private fun clientFor(provider: OAuthProvider): OAuthProviderClient =
        clients.firstOrNull { it.provider == provider } ?: invalid("지원하지 않는 외부 계정 제공자입니다.")
}
