package com.ogu.notification.stream

import com.ogu.shared.realtime.TopicBroadcaster
import org.springframework.stereotype.Component

/**
 * [TopicBroadcaster]의 구현(006 research R6, R7). 연결할 때 `topics`로 주제를 고른 연결에만 보낸다. 연결마다 주제별로
 * 가장 최근 값 하나만 들고 있다가 쓸 차례에 보내므로, 느린 연결에 값이 쌓이지 않고 마지막에는 최신 값이 간다.
 */
@Component
class StreamTopicBroadcaster(
    private val hub: SseHub,
) : TopicBroadcaster {
    override fun listenerCount(topic: String): Int = hub.connections.listenerCount(topic)

    override fun joinCount(topic: String): Long = hub.connections.joinCount(topic)

    override fun broadcast(
        topic: String,
        event: String,
        json: String,
    ) {
        val message = TopicMessage(event, json)
        hub.connections.listening(topic).forEach { connection ->
            connection.offer(topic, message)
            hub.writer.request(connection, StreamTask.BROADCAST)
        }
    }
}
