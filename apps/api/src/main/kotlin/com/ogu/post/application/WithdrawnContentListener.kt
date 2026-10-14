package com.ogu.post.application

import com.ogu.member.MemberWithdrawn
import org.springframework.context.event.EventListener
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation

/**
 * 회원이 탈퇴하면 그 회원의 글과 댓글을 치운다(013 research R2).
 *
 * - [remove]는 탈퇴와 같은 트랜잭션에서 돈다. 탈퇴가 커밋되면 글과 댓글은 이미 지운 것이고, 여기서 실패하면 탈퇴도
 *   되돌아간다(FR-007). 고민 글이라 "로그아웃은 됐는데 글이 아직 보인다"는 틈을 두지 않는다.
 * - [clean]은 커밋 뒤에 따로 돈다. 본문을 비우는 일은 글이 많으면 오래 걸리고, 실패하면 발행 기록에 남아 다시 온다(FR-017).
 */
@Component
class WithdrawnContentListener(
    private val remover: WithdrawnContentRemover,
    private val cleaner: WithdrawnContentCleaner,
) {
    @EventListener
    fun remove(event: MemberWithdrawn) {
        remover.removeAll(event.memberId, event.withdrawnAt)
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun clean(event: MemberWithdrawn) {
        cleaner.clean(event.memberId)
    }
}
