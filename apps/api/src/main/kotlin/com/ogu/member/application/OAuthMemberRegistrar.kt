package com.ogu.member.application

import com.ogu.member.domain.AuthMethod
import com.ogu.member.domain.EmailRegistrationLock
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.OAuthIdentity
import com.ogu.member.domain.OAuthIdentityRepository
import com.ogu.member.domain.OAuthProvider
import com.ogu.member.infrastructure.oauth.OAuthUserInfo
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 제공자가 확인해 준 사용자로 로그인하거나 새 회원을 만든다(research R5). 한 번 부를 때마다 한 트랜잭션이다.
 *
 * 1. 제공자가 검증한 이메일이 있으면 그 이메일의 잠금([EmailRegistrationLock])을 잡는다.
 * 2. 이미 연결된 외부 계정이면 이메일과 관계없이 그 회원으로 로그인한다.
 * 3. 검증된 이메일이 이메일 가입 회원과 같으면 `409 EMAIL_REGISTERED_WITH_OTHER_METHOD`(자동으로 합치지 않는다).
 *    다른 외부 계정 회원과는 이메일이 겹쳐도 된다.
 * 4. 새 회원(온보딩 전)과 연결을 만들고 세션을 발급한다.
 *
 * 검증되지 않은 이메일(구글 `email_verified=false`)은 충돌 판단에도 회원 이메일에도 쓰지 않는다. 그 이메일의 주인이
 * 나중에 이메일로 가입할 수 있어야 하기 때문이다. 연결의 `email`에는 참고용으로 남긴다.
 */
@Service
class OAuthMemberRegistrar(
    private val memberRepository: MemberRepository,
    private val identityRepository: OAuthIdentityRepository,
    private val sessionService: SessionService,
    private val emailLock: EmailRegistrationLock,
    private val clock: Clock,
) {
    @Transactional
    fun loginOrRegister(
        provider: OAuthProvider,
        user: OAuthUserInfo,
    ): OAuthLoginResult {
        val verifiedEmail = user.email.takeIf { user.emailVerified }?.let(::normalizedOrNull)
        // 연결 조회보다 먼저 잡아, 같은 이메일로 먼저 끝난 가입(이메일, 외부 계정)을 아래 조회가 모두 보게 한다
        verifiedEmail?.let(emailLock::lock)

        val identity = identityRepository.findByProviderAndProviderUserId(provider, user.providerUserId)
        if (identity != null) return login(identity)
        if (verifiedEmail != null) rejectIfEmailMember(verifiedEmail)

        val member = memberRepository.saveAndFlush(Member.registerWithOAuth(provider, verifiedEmail))
        identityRepository.saveAndFlush(
            OAuthIdentity.link(
                memberId = member.id,
                provider = provider,
                providerUserId = user.providerUserId,
                email = user.email?.let(::normalizedOrNull),
                now = clock.instant(),
            ),
        )
        val tokens = sessionService.issue(memberId = member.id, onboarded = false)
        return OAuthLoginResult(member = member, tokens = tokens, newMember = true)
    }

    private fun login(identity: OAuthIdentity): OAuthLoginResult {
        val member = memberRepository.findById(identity.memberId).orElseThrow()
        val tokens = sessionService.issue(memberId = member.id, onboarded = member.isOnboarded)
        return OAuthLoginResult(member = member, tokens = tokens, newMember = false)
    }

    private fun rejectIfEmailMember(email: String) {
        if (memberRepository.findAllByEmail(email).any { it.authMethod == AuthMethod.EMAIL }) {
            throw BusinessException(ErrorCode.EMAIL_REGISTERED_WITH_OTHER_METHOD)
        }
    }

    /** 정규화한 이메일. 비었거나 컬럼 길이(254자)를 넘으면 없는 것으로 본다. */
    private fun normalizedOrNull(raw: String): String? =
        Member.normalizeEmail(raw).takeIf { it.isNotEmpty() && it.length <= MAX_EMAIL_LENGTH }

    companion object {
        private const val MAX_EMAIL_LENGTH = 254
    }
}

data class OAuthLoginResult(
    val member: Member,
    val tokens: IssuedTokens,
    val newMember: Boolean,
)
