package com.ogu.member.domain

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 같은 이메일로 회원을 만드는 요청(이메일 가입, 첫 외부 로그인)을 직렬화하는 트랜잭션 범위 advisory lock.
 *
 * `member_email_key`는 이메일 가입끼리만 막으므로, 이메일 가입과 외부 계정 가입이 동시에 사전 확인을 지나면
 * 같은 이메일의 회원이 둘 생긴다. 사전 확인 전에 이 잠금을 잡으면 뒤 요청은 앞 요청이 커밋한 뒤에 확인한다.
 * 잠금은 트랜잭션이 끝날 때 풀린다. 키는 정규화한 이메일의 `hashtext`라 서로 다른 이메일이 드물게 같은 키를 쓸 수
 * 있지만, 그때는 서로 기다릴 뿐 결과는 같다.
 */
@Repository
class EmailRegistrationLock(
    private val jdbc: JdbcTemplate,
) {
    fun lock(normalizedEmail: String) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "EmailRegistrationLock은 트랜잭션 안에서만 쓸 수 있습니다."
        }
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", RowCallbackHandler { }, normalizedEmail)
    }
}
