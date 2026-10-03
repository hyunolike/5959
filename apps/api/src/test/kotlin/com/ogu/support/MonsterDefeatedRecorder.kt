package com.ogu.support

import com.ogu.monster.MonsterDefeated
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.event.EventListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 커밋된 [MonsterDefeated]를 모은다. 발행한 트랜잭션이 커밋된 뒤에만 세므로 롤백된 처치는 들어가지 않는다(M3 구독자가
 * 커밋 뒤에 받는 것과 같다). 어느 스레드에서 발행됐든(공감 요청, 비동기 몬스터 생성) 잡히도록 테스트 스레드에 묶인
 * `ApplicationEvents` 대신 빈 리스너를 쓴다. `@TransactionalEventListener`는 Event Publication Registry에 기록이 남으므로
 * 쓰지 않고 트랜잭션 동기화로 커밋 뒤에 담는다. 컨텍스트를 테스트끼리 나눠 쓰므로 글 ID로 걸러 읽는다.
 * [com.ogu.TestcontainersConfiguration]이 가져온다.
 */
@TestConfiguration(proxyBeanMethods = false)
class MonsterDefeatedRecorder {
    private val events = CopyOnWriteArrayList<MonsterDefeated>()

    @EventListener
    fun on(event: MonsterDefeated) {
        check(TransactionSynchronizationManager.isSynchronizationActive()) { "MonsterDefeated는 트랜잭션 안에서 발행된다." }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    events += event
                }
            },
        )
    }

    fun forPost(postId: Long): List<MonsterDefeated> = events.filter { it.postId == postId }
}
