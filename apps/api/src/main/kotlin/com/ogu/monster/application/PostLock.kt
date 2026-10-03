package com.ogu.monster.application

import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 글 단위 잠금(research R5). HP 반영(AttackListener)과 몬스터 생성(MonsterFactory)이 같은 글에서 겹치지 않게
 * 현재 트랜잭션에 `pg_advisory_xact_lock(postId)`을 잡는다. 잠금은 트랜잭션이 끝날 때 풀린다.
 * 트랜잭션 밖에서 잡으면 문장이 끝나자마자 풀려 아무것도 막지 못하므로 바로 실패한다.
 */
@Component
class PostLock(
    private val jdbcClient: JdbcClient,
) {
    fun lock(postId: Long) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "PostLock은 트랜잭션 안에서만 잡을 수 있습니다(postId=$postId)."
        }
        jdbcClient
            .sql("select pg_advisory_xact_lock(:postId)")
            .param("postId", postId)
            .query(RowCallbackHandler { })
    }
}
