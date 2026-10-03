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
    override fun getMember(memberId: Long): MemberInfo = getProfile(memberId).toInfo()

    override fun getMembers(ids: Collection<Long>): Map<Long, MemberInfo> {
        if (ids.isEmpty()) return emptyMap()
        return memberRepository.findAllById(ids.toSet()).associate { it.id to it.toInfo() }
    }

    fun getProfile(memberId: Long): Member {
        val member = memberRepository.findByIdOrNull(memberId)
        return member ?: throw BusinessException(ErrorCode.NOT_FOUND)
    }
}

private fun Member.toInfo(): MemberInfo =
    MemberInfo(
        id = id,
        nickname = nickname,
        jobRole = jobRole,
        careerYear = careerYear,
    )
