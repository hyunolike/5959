package com.ogu.notification.application

import com.ogu.notification.domain.NotificationRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.modulith.events.CompletedEventPublications
import java.time.Duration

/**
 * 정리 작업의 두 단계(알림, 끝난 이벤트 발행)는 서로의 실패에 가려지지 않는다(research R10). 한쪽이 실패해도 다른 쪽은
 * 돌고, 실패는 단계마다 ERROR로 남고, 스케줄러에도 실패로 보인다.
 */
@ExtendWith(OutputCaptureExtension::class)
class NotificationPurgeJobFailureTest {
    private val notifications = mock(NotificationRepository::class.java)
    private val publications = mock(CompletedEventPublications::class.java)
    private val job = NotificationPurgeJob(notifications, publications, NotificationProperties())

    @Test
    fun `알림 정리가 실패해도 이벤트 발행 정리는 돌고 알림 정리의 예외가 그대로 나온다`(output: CapturedOutput) {
        val failure = IllegalStateException("notification purge failed")
        `when`(notifications.deleteExpired(BATCH_SIZE)).thenThrow(failure)

        assertThatThrownBy { job.purge() }.isSameAs(failure)

        verify(publications).deletePublicationsOlderThan(PUBLICATION_RETENTION)
        assertThat(output.out).contains("ERROR").contains("notification purge failed")
    }

    @Test
    fun `이벤트 발행 정리가 실패해도 알림은 지워졌고 그 예외가 나온다`(output: CapturedOutput) {
        val failure = IllegalStateException("publication cleanup failed")
        `when`(notifications.deleteExpired(BATCH_SIZE)).thenReturn(3)
        doThrow(failure).`when`(publications).deletePublicationsOlderThan(PUBLICATION_RETENTION)

        assertThatThrownBy { job.purge() }.isSameAs(failure)

        verify(notifications).deleteExpired(BATCH_SIZE)
        assertThat(output.out).contains("ERROR").contains("publication cleanup failed")
    }

    @Test
    fun `둘 다 실패하면 알림 정리의 예외가 나오고 이벤트 발행 정리의 예외는 거기에 붙는다`(output: CapturedOutput) {
        val first = IllegalStateException("notification purge failed")
        val second = IllegalStateException("publication cleanup failed")
        `when`(notifications.deleteExpired(BATCH_SIZE)).thenThrow(first)
        doThrow(second).`when`(publications).deletePublicationsOlderThan(PUBLICATION_RETENTION)

        assertThatThrownBy { job.purge() }.isSameAs(first)

        assertThat(first.suppressed).containsExactly(second)
        assertThat(output.out).contains("notification purge failed").contains("publication cleanup failed")
    }

    private companion object {
        const val BATCH_SIZE = 1_000
        val PUBLICATION_RETENTION: Duration = Duration.ofDays(7)
    }
}
