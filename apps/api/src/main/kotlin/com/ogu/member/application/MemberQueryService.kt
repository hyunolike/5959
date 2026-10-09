package com.ogu.member.application

import com.ogu.member.MemberApi
import com.ogu.member.MemberInfo
import com.ogu.member.StreamTicket
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.MemberRole
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 회원 조회. 다른 모듈에는 [MemberApi]로, 이 모듈의 컨트롤러에는 [getProfile]로 제공한다.
 */
@Service
@Transactional(readOnly = true)
class MemberQueryService(
    private val memberRepository: MemberRepository,
    private val streamTickets: StreamTicketService,
) : MemberApi {
    override fun getMember(memberId: Long): MemberInfo = getProfile(memberId).toInfo()

    override fun getMembers(ids: Collection<Long>): Map<Long, MemberInfo> {
        if (ids.isEmpty()) return emptyMap()
        return memberRepository.findAllById(ids.toSet()).associate { it.id to it.toInfo() }
    }

    override fun isOperator(memberId: Long): Boolean = memberRepository.existsByIdAndRole(memberId, MemberRole.OPERATOR)

    // 연결 표는 쓰기라 읽기 전용 트랜잭션 밖에서 한 문장씩 자동 커밋한다
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun issueStreamTicket(
        memberId: Long,
        sessionId: UUID,
    ): StreamTicket = streamTickets.issue(memberId, sessionId)

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun consumeStreamTicket(ticket: String): Long? = streamTickets.consume(ticket)

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
