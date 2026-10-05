package com.ogu.notification.stream

import com.ogu.notification.application.NotificationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * SSE 전달의 스레드(research R2). 연결 하나는 서블릿 비동기 요청 하나라 기다리는 동안 요청 스레드를 쥐지 않는다. 쓰기와
 * 따라잡기는 전용 실행기([STREAM_EXECUTOR], `ogu.notification.writer-threads` 4개)가 하고, 하트비트와 안전망의 시각 맞추기는
 * 타이머 스레드 하나([StreamTimer])가 한다. 타이머는 할 일을 실행기에 넘기기만 한다.
 */
@Configuration(proxyBeanMethods = false)
class StreamConfig {
    @Bean(STREAM_EXECUTOR)
    fun notificationStreamExecutor(properties: NotificationProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.writerThreads
            maxPoolSize = properties.writerThreads
            queueCapacity = STREAM_QUEUE_CAPACITY
            setThreadNamePrefix("notification-stream-")
            setWaitForTasksToCompleteOnShutdown(false)
        }

    companion object {
        const val STREAM_EXECUTOR = "notificationStreamExecutor"

        /** 연결마다 실행기에 올라가는 일은 많아야 하나라(이미 올라가 있으면 표시만 한다) 연결 수만큼이면 넉넉하다. */
        private const val STREAM_QUEUE_CAPACITY = 20_000
    }
}
