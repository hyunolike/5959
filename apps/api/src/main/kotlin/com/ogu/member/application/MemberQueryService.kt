package com.ogu.member.application

import com.ogu.member.MemberApi
import com.ogu.member.MemberInfo
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 회원 조회. 다른 모듈에는 [MemberApi]로, 이 모듈의 컨트롤러에는 [getProfile]로 제공한다.
 */
@Service
@Transactional(readOnly = true)
class MemberQueryService(
    private val memberRepository: MemberRepository,
) : MemberApi {
    override fun getMember(memberId: Long): MemberInfo {
        val member = getProfile(memberId)
        return MemberInfo(
            id = member.id,
            nickname = member.nickname,
            jobRole = member.jobRole,
            careerYear = member.careerYear,
        )
    }

    fun getProfile(memberId: Long): Member {
        val member = memberRepository.findByIdOrNull(memberId)
        return member ?: throw BusinessException(ErrorCode.NOT_FOUND)
    }
}
