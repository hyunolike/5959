package com.ogu.member.application

import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.Nickname
import com.ogu.member.infrastructure.security.AccessToken
import com.ogu.member.infrastructure.security.JwtIssuer
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 닉네임 확인과 온보딩 완료(US1-AC4~AC6, FR-007~FR-009).
 */
@Service
class OnboardingService(
    private val memberRepository: MemberRepository,
    private val jwtIssuer: JwtIssuer,
    private val clock: Clock,
) {
    /** 형식이 틀리면 `INVALID_FORMAT`, 대소문자를 무시하고 이미 쓰이면 `TAKEN`. */
    @Transactional(readOnly = true)
    fun checkNickname(raw: String): NicknameAvailability =
        when {
            !Nickname.isValid(raw) -> NicknameAvailability.unavailable(NicknameUnavailableReason.INVALID_FORMAT)
            memberRepository.existsByNicknameKey(Nickname.of(raw).key) ->
                NicknameAvailability.unavailable(NicknameUnavailableReason.TAKEN)
            else -> NicknameAvailability.AVAILABLE
        }

    /**
     * 온보딩을 마치고 `onboarded=true` access 토큰을 새로 발급한다. 세션은 그대로라 토큰의 `sid`는 바뀌지 않는다.
     */
    @Transactional
    fun complete(
        memberId: Long,
        sessionId: UUID,
        nickname: String,
        jobRole: JobRole,
        careerYear: CareerYear,
    ): OnboardingResult {
        val member = lockNotOnboardedMember(memberId)
        val validated = Nickname.of(nickname)
        rejectIfNicknameTaken(validated)

        member.completeOnboarding(validated.value, jobRole, careerYear, clock.instant())
        try {
            memberRepository.saveAndFlush(member)
        } catch (e: DataIntegrityViolationException) {
            // 확인과 저장 사이에 다른 회원이 같은 닉네임으로 먼저 커밋한 경우. 온보딩에서 바뀌는 컬럼 중
            // 유일 제약이 있는 것은 nickname_key뿐이다.
            throw BusinessException(ErrorCode.NICKNAME_TAKEN).apply { initCause(e) }
        }
        val accessToken = jwtIssuer.issue(memberId = member.id, sessionId = sessionId, onboarded = true)
        return OnboardingResult(member = member, accessToken = accessToken)
    }

    /**
     * 같은 회원의 온보딩 요청이 겹쳐도 한 번만 반영되도록 행을 잠근다. 이미 온보딩했으면 닉네임 중복보다 먼저
     * `ALREADY_ONBOARDED`로 알린다.
     */
    private fun lockNotOnboardedMember(memberId: Long): Member {
        val member = memberRepository.findByIdForUpdate(memberId) ?: throw BusinessException(ErrorCode.NOT_FOUND)
        if (member.isOnboarded) throw BusinessException(ErrorCode.ALREADY_ONBOARDED)
        return member
    }

    private fun rejectIfNicknameTaken(nickname: Nickname) {
        if (memberRepository.existsByNicknameKey(nickname.key)) throw BusinessException(ErrorCode.NICKNAME_TAKEN)
    }
}

data class OnboardingResult(
    val member: Member,
    val accessToken: AccessToken,
)

enum class NicknameUnavailableReason {
    INVALID_FORMAT,
    TAKEN,
}

data class NicknameAvailability(
    val available: Boolean,
    val reason: NicknameUnavailableReason?,
) {
    companion object {
        val AVAILABLE = NicknameAvailability(available = true, reason = null)

        fun unavailable(reason: NicknameUnavailableReason) = NicknameAvailability(available = false, reason = reason)
    }
}
