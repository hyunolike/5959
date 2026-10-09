package com.ogu.shared.realtime

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 실시간 스트림에 주제별 소식을 내보낸다(006 research R6). 스트림은 notification 모듈에 있고, 내보내는 모듈(raid)은 이
 * 인터페이스만 본다. 그래서 raid가 notification에 의존하지 않고, notification이 raid의 이벤트를 받는 방향과 순환이 생기지
 * 않는다. 소식은 저장하지 않는다. 듣는 연결마다 주제별로 가장 최근 값 하나만 남겨 보낸다.
 */
interface TopicBroadcaster {
    /** 이 인스턴스에서 [topic]을 듣는 연결 수. 0이면 내보낼 것을 만들 필요가 없다. */
    fun listenerCount(topic: String): Int

    /** [topic]을 듣는 연결이 새로 붙을 때마다 커지는 수. 달라졌으면 새 연결이 지금 값을 받도록 다시 내보낸다. */
    fun joinCount(topic: String): Long

    /** [topic]을 듣는 연결에 [event] 이름으로 [json]을 보낸다. SSE `id`는 붙이지 않는다. */
    fun broadcast(
        topic: String,
        event: String,
        json: String,
    )
}

/** 스트림이 없는 컨텍스트(모듈 테스트)에서는 듣는 연결이 없는 것으로 답한다. */
@Configuration(proxyBeanMethods = false)
class TopicBroadcasterConfig {
    @Bean
    @ConditionalOnMissingBean(TopicBroadcaster::class)
    fun noTopicBroadcaster(): TopicBroadcaster =
        object : TopicBroadcaster {
            override fun listenerCount(topic: String): Int = 0

            override fun joinCount(topic: String): Long = 0

            override fun broadcast(
                topic: String,
                event: String,
                json: String,
            ) = Unit
        }
}
