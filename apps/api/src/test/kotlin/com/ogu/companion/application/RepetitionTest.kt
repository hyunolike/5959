package com.ogu.companion.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 뒤따르는 AI 친구의 댓글이 앞 댓글을 되풀이하는지 본다(012 research R6). */
class RepetitionTest {
    private val previous = listOf("그랬군요, 세 시간 회의라니 많이 지쳤겠어요. 오늘은 푹 쉬어도 괜찮아요.")

    @Test
    fun `FR-010 앞 댓글과 똑같은 답은 되풀이다`() {
        assertThat(Repetition.repeats(previous.single(), previous)).isTrue()
    }

    @Test
    fun `FR-010 띄어쓰기와 문장 부호만 다른 답도 되풀이다`() {
        val respaced = "그랬군요 세시간 회의라니 많이 지쳤겠어요! 오늘은 푹 쉬어도 괜찮아요"

        assertThat(Repetition.repeats(respaced, previous)).isTrue()
    }

    @Test
    fun `FR-010 앞 댓글의 문장 하나를 그대로 쓴 답은 되풀이다`() {
        val borrowed = "준비한 말을 못 꺼내면 속상하죠. 오늘은 푹 쉬어도 괜찮아요."

        assertThat(Repetition.repeats(borrowed, previous)).isTrue()
    }

    @Test
    fun `FR-010 다른 것을 짚은 답은 되풀이가 아니다`() {
        val fresh = "준비한 말을 끝내 못 꺼냈다니 속상했겠어요. 내일은 한 문장만 먼저 던져 보면 어때요."

        assertThat(Repetition.repeats(fresh, previous)).isFalse()
    }

    @Test
    fun `FR-010 짧은 맞장구가 겹치는 것은 되풀이로 보지 않는다`() {
        val shortEcho = "그랬군요. 준비한 말을 끝내 못 꺼냈다니 속상했겠어요."

        assertThat(Repetition.repeats(shortEcho, listOf("그랬군요. 세 시간 회의라니 많이 지쳤겠어요."))).isFalse()
    }

    @Test
    fun `FR-010 앞 댓글이 없으면 되풀이가 아니다`() {
        assertThat(Repetition.repeats(previous.single(), emptyList())).isFalse()
    }
}
