package com.ogu.notification.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 회원별 전달 번호 카운터 `notification_sequence`(research R4).
 *
 * [next]가 잡은 행 잠금은 커밋까지 남아 같은 회원의 알림 쓰기를 줄 세운다. 그래서 커밋 순서와 번호 순서가 같다.
 * 한 트랜잭션에서 여러 회원의 번호를 받을 때는 회원 ID 오름차순으로 받는다(교착 방지). 트랜잭션 밖에서 받으면 잠금이 문장
 * 끝에 풀려 이 보장이 깨지므로 [next]는 트랜잭션 안에서만 부를 수 있다.
 */
@Repository
class NotificationSequenceRepository(
    private val jdbcClient: JdbcClient,
) {
    fun next(memberId: Long): Long {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "알림 번호는 알림을 쓰는 트랜잭션 안에서만 받는다"
        }
        return jdbcClient
            .sql(
                """
                insert into notification_sequence (member_id, last_seq) values (:memberId, 1)
                on conflict (member_id) do update set last_seq = notification_sequence.last_seq + 1
                returning last_seq
                """.trimIndent(),
            ).param("memberId", memberId)
            .query(Long::class.java)
            .single()
    }

    /** 이 회원에게 마지막으로 준 번호. 아직 없으면 0이다. */
    fun current(memberId: Long): Long =
        jdbcClient
            .sql("select last_seq from notification_sequence where member_id = :memberId")
            .param("memberId", memberId)
            .query(Long::class.java)
            .optional()
            .orElse(0L)
}
