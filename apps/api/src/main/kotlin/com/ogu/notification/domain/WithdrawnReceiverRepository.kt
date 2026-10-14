package com.ogu.notification.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * 탈퇴한 회원이 받은 것을 지운다(013 FR-008, research R7). 알림 목록과 번호를 다루는 저장소와 나눠 둔다.
 * 평소의 쓰기와 읽기는 이 길을 쓰지 않는다.
 */
@Repository
class WithdrawnReceiverRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * 회원이 받은 알림과 번호 행을 지우고 지운 알림의 수를 돌려준다. 공감 묶음의 참여자 행은 FK `ON DELETE CASCADE`로
     * 함께 지워진다. 다른 회원이 받은 알림에서 이 회원이 행동한 회원으로 남은 것은 건드리지 않는다. 탈퇴한 회원에게는
     * 알림을 더 쓰지 않으므로 번호가 다시 쓰일 일이 없다.
     */
    fun deleteAllOf(memberId: Long): Int {
        val notifications =
            jdbcClient
                .sql("delete from notification where receiver_id = :memberId")
                .param("memberId", memberId)
                .update()
        jdbcClient
            .sql("delete from notification_sequence where member_id = :memberId")
            .param("memberId", memberId)
            .update()
        return notifications
    }
}
