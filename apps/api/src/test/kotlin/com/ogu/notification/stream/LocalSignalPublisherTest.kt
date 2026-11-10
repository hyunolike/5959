package com.ogu.notification.stream

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@ExtendWith(OutputCaptureExtension::class)
class LocalSignalPublisherTest {
    @Test
    fun `처리기 하나가 실패해도 다른 처리기는 받는다`() {
        val received = mutableListOf<NotificationSignal>()
        val failing = NotificationSignalHandler { error("처리 실패") }
        val publisher = LocalSignalPublisher(listOf(failing, NotificationSignalHandler(received::add)), DIRECT)

        publisher.publish(NotificationSignal.Read(1L))
        publisher.publish(NotificationSignal.Closed(2L))

        assertThat(received).containsExactly(NotificationSignal.Read(1L), NotificationSignal.Closed(2L))
    }

    @Test
    fun `대기열이 가득 차면 버리고 경고는 1분에 한 번만 남긴다`(output: CapturedOutput) {
        val full = Executor { throw RejectedExecutionException("가득 참") }
        val publisher = LocalSignalPublisher(listOf(NotificationSignalHandler { error("오면 안 된다") }), full)

        repeat(3) { publisher.publish(NotificationSignal.New(1L, it.toLong())) }

        assertThat(Regex("이 경고는 1분에 한 번").findAll(output.out).count()).isEqualTo(1)
    }

    @Test
    fun `신호는 전용 스레드 하나와 대기열 1만 개로 넘긴다`() {
        val executor = LocalSignalConfig().notificationSignalExecutor()

        assertThat(executor.corePoolSize).isEqualTo(1)
        assertThat(executor.maxPoolSize).isEqualTo(1)
        assertThat(executor.queueCapacity).isEqualTo(10_000)
    }

    private companion object {
        val DIRECT = Executor(Runnable::run)
    }
}
