package com.ogu.member.application

import com.ogu.member.domain.AuthMethod
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.PasswordResetTokenRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 링크로 새 비밀번호를 정한다(015 US2, research R5). 한 트랜잭션에서 링크를 쓰고, 비밀번호를 바꾸고, 그 회원의 세션을
 * 모두 끝내고, 그 이메일의 로그인 시도 제한을 지운다. 어느 하나가 실패하면 모두 되돌아가 링크도 쓰지 않은 채로 남는다.
 * 로그인시키지 않는다.
 */
@Service
class PasswordResetConfirmation(
    private val memberRepository: MemberRepository,
    private val tokens: PasswordResetTokenRepository,
    private val credentials: MemberCredentials,
    private val loginThrottle: LoginThrottle,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 비밀번호 규칙을 링크보다 먼저 본다. 규칙에 걸린 저장이 링크를 써 버리지 않는다(US2-AC6). 쓸 수 없는 링크는 까닭을
     * 가르지 않고 `400 PASSWORD_RESET_LINK_INVALID`다(FR-007). 링크가 맞은 뒤에만 비밀번호를 해시한다. 지어낸 값으로
     * 해시 계산을 시킬 수 없다.
     */
    @Transactional
    fun confirm(
        token: String,
        password: String,
    ) {
        PasswordPolicy.validate(password)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val member =
            token.takeIf { it.isNotEmpty() }?.let { lockAndConsume(RefreshTokens.hash(it), now) }
                ?: throw BusinessException(ErrorCode.PASSWORD_RESET_LINK_INVALID)
        member.changePassword(requireNotNull(passwordEncoder.encode(password)))
        memberRepository.flush()
        credentials.signOutEverywhere(member.id)
        // 로그인을 여러 번 틀려 막혀 있던 회원도 새 비밀번호로 바로 들어온다(FR-017). 막힌 곳이 이 기기가 아니어도 풀린다
        loginThrottle.forgive(requireNotNull(member.email))
        log.info("비밀번호를 재설정했습니다: memberId={}", member.id)
    }

    /**
     * 회원 행을 먼저 잠그고 링크를 쓴다. 탈퇴(013)도 회원 행, 링크 순서로 잠근다. 링크를 먼저 쓰면 둘이 겹칠 때 서로를
     * 기다려 교착이 난다. 회원이 없거나 탈퇴했으면 링크를 쓰지 않고 null이다.
     */
    private fun lockAndConsume(
        tokenHash: String,
        now: Instant,
    ): Member? {
        val member = tokens.memberOf(tokenHash)?.let(memberRepository::findByIdForUpdate)?.takeIf(::canReset)
        return member?.takeIf { tokens.consume(tokenHash, now) == it.id }
    }

    private fun canReset(member: Member): Boolean {
        val emailMember = member.authMethod == AuthMethod.EMAIL && member.email != null
        return emailMember && !member.isWithdrawn
    }
}
