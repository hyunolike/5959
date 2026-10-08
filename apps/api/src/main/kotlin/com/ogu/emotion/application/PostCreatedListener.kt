package com.ogu.emotion.application

import com.ogu.post.PostCreated
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation

/**
 * 글이 커밋되면(비동기) 분석 행을 만들고 바로 첫 시도를 한다(research R2).
 * 리스너 자체는 트랜잭션 없이 돈다(`NOT_SUPPORTED`). LLM 호출 동안 DB 커넥션을 쥐고 있지 않도록 행 생성, 맡기, 기록을
 * 각각 짧은 트랜잭션으로 나눈다. 분석이 실패해도 리스너는 정상 종료하고 재시도는 스케줄러가 맡는다.
 */
@Component
class PostCreatedListener(
    private val store: AnalysisStore,
    private val runner: AnalysisRunner,
) {
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: PostCreated) {
        store.createPending(event)
        runner.attempt(event.postId)
    }
}
