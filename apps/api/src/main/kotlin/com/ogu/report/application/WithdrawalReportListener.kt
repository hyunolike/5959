package com.ogu.report.application

import com.ogu.member.MemberWithdrawn
import com.ogu.report.domain.WeeklyReportRepository
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * 회원이 탈퇴하면 그 회원의 주간 리포트와 편지를 지운다(013 FR-008, 008이 미뤄 둔 일). 커밋 뒤 새 트랜잭션에서 돌고,
 * 실패하면 발행 기록에 남아 다시 온다. 편지를 쓰던 중이었으면 그 차례는 행이 없어 아무것도 적지 못하고 끝난다.
 *
 * 탈퇴한 회원의 새 리포트는 만들어지지 않는다. 대상은 지난주에 글을 쓴 회원이고 지운 글은 세지 않는다.
 */
@Component
class WithdrawalReportListener(
    private val reports: WeeklyReportRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun on(event: MemberWithdrawn) {
        val deleted = reports.deleteAllOfMember(event.memberId)
        log.info("탈퇴한 회원의 주간 리포트를 지웠습니다: memberId={}, reports={}", event.memberId, deleted)
    }
}
