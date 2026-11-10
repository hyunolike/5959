package com.ogu.shared.schedule

import com.ogu.shared.config.EventPublicationResubmitter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/** 018 T011: 한 단계가 실패해도 다른 단계와 마무리(타이머 다시 걸기, 이벤트 재전송)는 돈다. */
class HousekeepingStepFailureTest {
    private val ran = mutableListOf<String>()

    private fun step(
        name: String,
        order: Int,
        fail: Boolean = false,
    ) = object : HousekeepingStep {
        override val stepName = name
        override val order = order

        override fun runStep() {
            ran += name
            check(!fail) { "$name 실패" }
        }
    }

    @Test
    fun `한 단계가 실패해도 다른 단계는 돈다`() {
        val timers = mock(DueTimers::class.java)
        val resubmitter = mock(EventPublicationResubmitter::class.java)
        val housekeeping =
            Housekeeping(
                listOf(step("셋", 30), step("하나", 10, fail = true), step("둘", 20)),
                timers,
                resubmitter,
            )

        housekeeping.run()

        assertThat(ran).containsExactly("하나", "둘", "셋")
        verify(timers).rearmAll()
        verify(resubmitter).resubmit()
    }

    @Test
    fun `타이머 다시 걸기가 실패해도 이벤트는 다시 보낸다`() {
        val timers = mock(DueTimers::class.java)
        willThrow(IllegalStateException("DB에 닿지 못함")).given(timers).rearmAll()
        val resubmitter = mock(EventPublicationResubmitter::class.java)

        Housekeeping(emptyList(), timers, resubmitter).run()

        verify(resubmitter).resubmit()
    }
}
