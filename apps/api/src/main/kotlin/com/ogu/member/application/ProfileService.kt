package com.ogu.member.application

import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.Nickname
import com.ogu.member.infrastructure.persistence.UniqueConstraints
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 프로필 수정(004 US5, research R13). 보낸 항목만 바꾼다. 닉네임 규칙과 중복 판단은 온보딩과 같다.
 * 글에 남긴 직군과 경력은 작성 시점 스냅숏이라 건드리지 않고(US5-AC3), 닉네임은 어디서나 회원의 지금 값을 읽으므로
 * 따로 알릴 것이 없다(US5-AC4). 그래서 이벤트를 내지 않는다.
 */
@Service
class ProfileService(
    private val memberRepository: MemberRepository,
) {
    @Transactional
    fun update(
        memberId: Long,
        nickname: String?,
        jobRole: JobRole?,
        careerYear: CareerYear?,
    ): Member {
        if (nickname == null && jobRole == null && careerYear == null) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "바꿀 항목을 하나 이상 보내 주세요.")
        }
        val member = lockOnboardedMember(memberId)
        val validated = nickname?.let(Nickname::of)
        // 내 닉네임의 대소문자만 바꾸는 것은 중복이 아니다
        if (validated != null && validated.key != member.nicknameKey) rejectIfNicknameTaken(validated)

        member.updateProfile(validated, jobRole, careerYear)
        return save(member)
    }

    /** 같은 회원의 수정이 겹쳐도 차례로 반영되게 행을 잠근다. 온보딩 전이면 `ONBOARDING_REQUIRED`다. */
    private fun lockOnboardedMember(memberId: Long): Member {
        val member = memberRepository.findByIdForUpdate(memberId) ?: throw BusinessException(ErrorCode.NOT_FOUND)
        if (!member.isOnboarded) throw BusinessException(ErrorCode.ONBOARDING_REQUIRED)
        return member
    }

    private fun save(member: Member): Member =
        try {
            memberRepository.saveAndFlush(member)
        } catch (e: DataIntegrityViolationException) {
            // 확인과 저장 사이에 다른 회원이 같은 닉네임으로 먼저 커밋한 경우. 다른 제약 위반은 409로 숨기지 않는다.
            if (!UniqueConstraints.isViolated(e, UniqueConstraints.MEMBER_NICKNAME_KEY)) throw e
            throw BusinessException(ErrorCode.NICKNAME_TAKEN).apply { initCause(e) }
        }

    private fun rejectIfNicknameTaken(nickname: Nickname) {
        if (memberRepository.existsByNicknameKey(nickname.key)) throw BusinessException(ErrorCode.NICKNAME_TAKEN)
    }
}
