package com.ogu.member.application

import com.ogu.member.domain.AuthMethod
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 이메일 로그인(US2-AC1~AC4, FR-003, FR-004).
 *
 * 순서: 이메일 정규화 → 차단 확인(막혀 있으면 비밀번호를 검증하지 않는다) → 회원 조회 → 비밀번호 검증 → 실패 기록 또는
 * 성공 처리 → 세션 발급. 회원이 없어도 더미 해시로 bcrypt를 한 번 돌려 응답 시간으로 가입 여부가 드러나지 않게 한다.
 *
 * 전체를 한 트랜잭션으로 묶지 않는다. 실패 기록은 `401`을 던진 뒤에도 남아야 하기 때문이다. 실패 기록과 세션 발급은
 * 각자 트랜잭션에서 커밋된다.
 */
@Service
class LoginService(
    private val memberRepository: MemberRepository,
    private val sessionService: SessionService,
    private val passwordEncoder: PasswordEncoder,
    private val throttle: LoginThrottle,
) {
    // 기동할 때 한 번 만든다. 원문은 버리므로 어떤 입력도 이 해시와 맞지 않는다.
    private val dummyHash: String = requireNotNull(passwordEncoder.encode(UUID.randomUUID().toString()))

    fun login(
        rawEmail: String,
        password: String,
        clientIp: String,
    ): LoginResult {
        val email = Member.normalizeEmail(rawEmail)
        validate(email, password)
        throttle.checkNotBlocked(clientIp, email)

        val member = memberRepository.findAllByEmail(email).firstOrNull { it.authMethod == AuthMethod.EMAIL }
        if (!passwordMatches(member, password)) {
            throttle.recordFailure(clientIp, email)
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        }
        val loggedIn = requireNotNull(member)
        throttle.recordSuccess(clientIp, email)
        val tokens = sessionService.issue(memberId = loggedIn.id, onboarded = loggedIn.isOnboarded)
        return LoginResult(member = loggedIn, tokens = tokens)
    }

    private fun passwordMatches(
        member: Member?,
        password: String,
    ): Boolean {
        val hash = member?.passwordHash
        return when {
            // bcrypt는 72바이트까지만 받는다. 가입 때 이 한도를 넘는 비밀번호는 거절하므로 어떤 회원과도 맞지 않는다.
            password.toByteArray(Charsets.UTF_8).size > BCRYPT_MAX_BYTES -> false
            hash == null -> {
                passwordEncoder.matches(password, dummyHash)
                false
            }
            else -> passwordEncoder.matches(password, hash)
        }
    }

    private fun validate(
        email: String,
        password: String,
    ) {
        if (email.isEmpty() || email.length > MAX_EMAIL_LENGTH || password.isEmpty()) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "이메일과 비밀번호를 입력해 주세요.")
        }
    }

    companion object {
        private const val MAX_EMAIL_LENGTH = 254
        private const val BCRYPT_MAX_BYTES = 72
    }
}

data class LoginResult(
    val member: Member,
    val tokens: IssuedTokens,
)
