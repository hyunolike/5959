package com.ogu.member.application

import com.ogu.member.domain.AuthMethod
import com.ogu.member.domain.EmailRegistrationLock
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.member.infrastructure.persistence.UniqueConstraints
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 이메일 가입(US1-AC1~AC3, FR-001, FR-002). 회원 생성과 세션 발급을 한 트랜잭션으로 처리해 가입과 동시에 로그인된다.
 * 사전 확인 전에 이메일 잠금([EmailRegistrationLock])을 잡아, 같은 이메일의 첫 외부 로그인과 겹쳐도 회원이 하나만 생긴다.
 */
@Service
class SignupService(
    private val memberRepository: MemberRepository,
    private val sessionService: SessionService,
    private val passwordEncoder: PasswordEncoder,
    private val emailLock: EmailRegistrationLock,
) {
    @Transactional
    fun signup(
        rawEmail: String,
        password: String,
    ): SignupResult {
        val email = Member.normalizeEmail(rawEmail)
        EmailFormat.validate(email)
        PasswordPolicy.validate(password)
        emailLock.lock(email)
        rejectIfEmailInUse(email)

        val passwordHash = requireNotNull(passwordEncoder.encode(password))
        val member =
            try {
                memberRepository.saveAndFlush(Member.registerWithEmail(email, passwordHash))
            } catch (e: DataIntegrityViolationException) {
                // 확인과 저장 사이에 같은 이메일의 이메일 가입이 먼저 커밋된 경우(잠금이 있어 정상 흐름에서는 드물다).
                // 다른 제약 위반은 409로 숨기지 않는다.
                if (!UniqueConstraints.isViolated(e, UniqueConstraints.MEMBER_EMAIL)) throw e
                throw BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED).apply { initCause(e) }
            }
        val tokens = sessionService.issue(memberId = member.id, onboarded = false)
        return SignupResult(member = member, tokens = tokens)
    }

    private fun rejectIfEmailInUse(email: String) {
        val existing = memberRepository.findAllByEmail(email)
        when {
            existing.any { it.authMethod == AuthMethod.EMAIL } ->
                throw BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED)
            existing.isNotEmpty() ->
                throw BusinessException(ErrorCode.EMAIL_REGISTERED_WITH_OTHER_METHOD)
        }
    }
}

data class SignupResult(
    val member: Member,
    val tokens: IssuedTokens,
)
