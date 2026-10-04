package com.ogu.shared.lock

import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 글 단위 잠금(research R5). HP 반영(monster 모듈의 AttackListener), 몬스터 생성(MonsterFactory), 글 삭제(post 모듈의
 * PostService)가 같은 글에서 겹치지 않게 현재 트랜잭션에 `pg_advisory_xact_lock(NAMESPACE, key(postId))`을 잡는다.
 * 두 정수 키 형식이라 bigint 키 하나를 쓰는 이메일 가입 잠금(`hashtext(email)`)과 키 공간이 겹치지 않는다. 두 모듈이 같은
 * 키를 써야 하므로 shared에 둔다. 잠금 순서는 언제나 `posts`나 `comments` 행을 먼저 잠그고(카운터나 deleted_at UPDATE)
 * 이 잠금을 나중에 잡는다. MonsterFactory는 이 잠금만 잡고 posts 행은 읽기만 한다. 잠금은 트랜잭션이 끝날 때 풀린다.
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
            .sql("select pg_advisory_xact_lock(:namespace, :key)")
            .param("namespace", NAMESPACE)
            .param("key", key(postId))
            .query(RowCallbackHandler { })
    }

    companion object {
        /** 이 잠금만 쓰는 키 공간("plck"). 작성 제한(PostRateLimit, "post")과 이메일 가입 잠금(bigint 키 하나)과 겹치지 않는다. */
        const val NAMESPACE = 0x706c636b

        /** 글 ID를 32비트 키로 접는다. 2^31보다 작은 ID는 서로 다른 키가 되고, 겹치더라도 두 글이 잠시 함께 기다릴 뿐이다. */
        fun key(postId: Long): Int = (postId xor (postId ushr Int.SIZE_BITS)).toInt()
    }
}
