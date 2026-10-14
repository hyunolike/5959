package com.ogu.member.application

import com.ogu.member.MemberWithdrawn
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberKind
import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.MemberRole
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 회원 탈퇴(013 US1, research R1, R2). 한 트랜잭션에서 회원 행을 비우고, 로그인 수단과 세션을 지우고, [MemberWithdrawn]을 낸다.
 * `post`가 그 이벤트를 같은 트랜잭션에서 받아 글과 댓글을 지운 것으로 바꾸므로, 탈퇴와 "글이 보이지 않는다"가 함께
 * 커밋되거나 함께 되돌아간다(FR-007).
 *
 * 회원 행을 `FOR UPDATE`로 잠근다. 같은 회원의 겹친 탈퇴는 줄을 서고, 두 번째는 탈퇴한 행을 보고 401이다(US5-AC5).
 * 그 회원의 글과 댓글의 INSERT는 FK 때문에 같은 행을 기다리므로 탈퇴와 겹치지 않는다(research R5).
 */
@Service
class WithdrawalService(
    private val memberRepository: MemberRepository,
    private val credentials: MemberCredentials,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 운영자는 `409 OPERATOR_CANNOT_WITHDRAW`다. 지정을 푼 뒤에 한다(research R6). 이미 탈퇴했으면
     * `401 SESSION_EXPIRED`다. AI 친구는 로그인할 수 없어 여기에 닿지 않지만 닿아도 거절한다.
     */
    @Transactional
    fun withdraw(memberId: Long) {
        val member = lockActive(memberId)
        if (member.role == MemberRole.OPERATOR) throw BusinessException(ErrorCode.OPERATOR_CANNOT_WITHDRAW)
        if (member.kind != MemberKind.HUMAN) throw BusinessException(ErrorCode.FORBIDDEN)

        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        member.withdraw(now)
        // 뒤따르는 삭제와 리스너가 JDBC로 쓰므로 회원 행을 먼저 내보낸다. 탈퇴한 행의 제약도 여기서 확인된다
        memberRepository.flush()
        // 세션, 연결 표, 외부 계정 연결, 비밀번호 재설정 링크(015 FR-018)
        credentials.deleteAll(memberId)
        events.publishEvent(MemberWithdrawn(memberId, now))
        log.info("회원이 탈퇴했습니다: memberId={}", memberId)
    }

    /** 회원 행을 잠그고 읽는다. 없거나, 잠금을 기다리는 사이 다른 요청이 먼저 탈퇴시켰으면 `401 SESSION_EXPIRED`다. */
    private fun lockActive(memberId: Long): Member {
        val member = memberRepository.lockForWithdrawal(memberId)?.let { memberRepository.findByIdOrNull(it) }
        if (member == null || member.isWithdrawn) throw BusinessException(ErrorCode.SESSION_EXPIRED)
        return member
    }
}
