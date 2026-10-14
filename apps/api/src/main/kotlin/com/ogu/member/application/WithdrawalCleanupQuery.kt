package com.ogu.member.application

import com.ogu.member.MemberWithdrawn
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 정리가 끝나지 않은 탈퇴(013 FR-018, research R8). 탈퇴 뒤의 정리(본문 비우기, 받은 알림과 주간 리포트 지우기)는
 * [MemberWithdrawn]을 받는 리스너들이 하고, 리스너마다 끝났는지는 Event Publication Registry에 남는다. 그것을 센다.
 * 진행을 적는 테이블을 따로 두지 않는다.
 *
 * 한 탈퇴에 리스너가 여럿이라 발행 행도 여럿이다. 탈퇴의 수는 이벤트(회원 번호와 때)로 묶어 센다. 열 번 실패해 더는 다시
 * 보내지 않는 발행도 끝나지 않은 것으로 남으므로, 이 수가 줄지 않으면 운영자가 로그를 본다.
 */
@Component
class WithdrawalCleanupQuery(
    private val jdbcClient: JdbcClient,
) {
    fun status(): WithdrawalCleanupStatus =
        jdbcClient
            .sql(
                """
                select count(distinct serialized_event) as pending, min(publication_date) as oldest
                from event_publication
                where event_type = :eventType and completion_date is null
                """.trimIndent(),
            ).param("eventType", MemberWithdrawn::class.java.name)
            .query { rs, _ ->
                WithdrawalCleanupStatus(
                    pending = rs.getInt("pending"),
                    oldestWithdrawnAt = rs.getTimestamp("oldest")?.toInstant(),
                )
            }.single()
}

data class WithdrawalCleanupStatus(
    val pending: Int,
    val oldestWithdrawnAt: Instant?,
)
