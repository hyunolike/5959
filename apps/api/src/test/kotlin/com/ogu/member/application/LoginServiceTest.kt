package com.ogu.member.application

import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID

/**
 * T046: 로그인 순서(차단 확인 → 회원 조회 → 비밀번호 검증 → 실패 기록 또는 성공 처리)와 bcrypt 호출 여부.
 */
class LoginServiceTest {
    private val memberRepository: MemberRepository = mock(MemberRepository::class.java)
    private val sessionService: SessionService = mock(SessionService::class.java)
    private val passwordEncoder: PasswordEncoder = mock(PasswordEncoder::class.java)
    private val throttle: LoginThrottle = mock(LoginThrottle::class.java)

    init {
        given(passwordEncoder.encode(anyString())).willReturn(DUMMY_HASH)
    }

    private val service = LoginService(memberRepository, sessionService, passwordEncoder, throttle)

    @Test
    fun `차단 중이면 회원을 찾거나 비밀번호를 검증하지 않고 LOGIN_THROTTLED`() {
        willThrow(BusinessException(ErrorCode.LOGIN_THROTTLED, retryAfterSeconds = 60))
            .given(throttle)
            .checkNotBlocked(IP, EMAIL)

        val e = catchThrowableOfType(BusinessException::class.java) { service.login(" $EMAIL ", PASSWORD, IP) }

        assertThat(e.errorCode).isEqualTo(ErrorCode.LOGIN_THROTTLED)
        assertThat(e.retryAfterSeconds).isEqualTo(60)
        verify(passwordEncoder, never()).matches(anyString(), anyString())
        verifyNoInteractions(memberRepository, sessionService)
        verify(throttle, never()).recordFailure(anyString(), anyString())
    }

    @Test
    fun `없는 이메일이어도 더미 해시로 비밀번호를 한 번 검증하고 실패로 센다`() {
        given(memberRepository.findAllByEmail(EMAIL)).willReturn(emptyList())

        val e = catchThrowableOfType(BusinessException::class.java) { service.login(EMAIL.uppercase(), PASSWORD, IP) }

        assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_CREDENTIALS)
        verify(passwordEncoder, times(1)).matches(PASSWORD, DUMMY_HASH)
        verify(throttle).recordFailure(IP, EMAIL)
        verifyNoInteractions(sessionService)
    }

    @Test
    fun `비밀번호가 틀리면 실패로 세고 세션을 만들지 않는다`() {
        val member = Member.registerWithEmail(EMAIL, MEMBER_HASH)
        given(memberRepository.findAllByEmail(EMAIL)).willReturn(listOf(member))
        given(passwordEncoder.matches(PASSWORD, MEMBER_HASH)).willReturn(false)

        val e = catchThrowableOfType(BusinessException::class.java) { service.login(EMAIL, PASSWORD, IP) }

        assertThat(e.errorCode).isEqualTo(ErrorCode.INVALID_CREDENTIALS)
        verify(passwordEncoder, times(1)).matches(anyString(), anyString())
        verify(throttle).recordFailure(IP, EMAIL)
        verifyNoInteractions(sessionService)
    }

    @Test
    fun `비밀번호가 맞으면 IP와 이메일 키를 지우고 세션을 발급한다`() {
        val member = Member.registerWithEmail(EMAIL, MEMBER_HASH)
        given(memberRepository.findAllByEmail(EMAIL)).willReturn(listOf(member))
        given(passwordEncoder.matches(PASSWORD, MEMBER_HASH)).willReturn(true)
        val tokens =
            IssuedTokens(
                sessionId = UUID.randomUUID(),
                accessToken = "access",
                accessTokenExpiresAt = Instant.EPOCH,
                refreshToken = "refresh",
                refreshTokenExpiresAt = Instant.EPOCH,
            )
        given(sessionService.issue(memberId = member.id, onboarded = false)).willReturn(tokens)

        val result = service.login(EMAIL, PASSWORD, IP)

        assertThat(result.member).isSameAs(member)
        assertThat(result.tokens).isSameAs(tokens)

        verify(throttle).recordSuccess(IP, EMAIL)
        verify(throttle, never()).recordFailure(anyString(), anyString())
        verify(sessionService).issue(memberId = member.id, onboarded = false)
    }

    companion object {
        private const val EMAIL = "user@example.com"
        private const val PASSWORD = "password123"
        private const val IP = "203.0.113.1"
        private const val DUMMY_HASH = "{bcrypt}dummy"
        private const val MEMBER_HASH = "{bcrypt}member"
    }
}
