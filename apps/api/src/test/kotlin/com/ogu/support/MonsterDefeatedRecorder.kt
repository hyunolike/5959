package com.ogu.support

import com.ogu.monster.MonsterDefeated
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.event.EventListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 발행된 [MonsterDefeated]를 모은다. 어느 스레드에서 발행됐든(공감 요청, 비동기 몬스터 생성) 잡히도록 테스트 스레드에
 * 묶인 `ApplicationEvents` 대신 빈 리스너를 쓴다. 컨텍스트를 테스트끼리 나눠 쓰므로 글 ID로 걸러 읽는다.
 * [com.ogu.TestcontainersConfiguration]이 가져온다.
 */
@TestConfiguration(proxyBeanMethods = false)
class MonsterDefeatedRecorder {
    private val events = CopyOnWriteArrayList<MonsterDefeated>()

    @EventListener
    fun on(event: MonsterDefeated) {
        events += event
    }

    fun forPost(postId: Long): List<MonsterDefeated> = events.filter { it.postId == postId }
}
