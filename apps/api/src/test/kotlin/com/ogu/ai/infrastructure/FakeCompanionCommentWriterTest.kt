package com.ogu.ai.infrastructure

import com.ogu.ai.CompanionCommentFailed
import com.ogu.ai.CompanionCommentInput
import com.ogu.ai.CompanionTone
import com.ogu.ai.CompanionVoice
import com.ogu.ai.EmotionAnalysisFailed
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 가짜 댓글 쓰기의 표지(009 quickstart "준비"의 표). */
class FakeCompanionCommentWriterTest {
    private val writer = FakeCompanionCommentWriter()

    @Test
    fun `US4-AC1 표지가 없으면 말투와 말씨의 이름이 든 정해진 댓글을 준다`() {
        val comment = writer.write(KEY, input("오늘 회의가 길었어요", CompanionTone.WARM_ADVICE, CompanionVoice.CALM))

        assertThat(comment).contains("따뜻한 조언해주기", "차분한 오구")
        // 정해진 댓글은 실제 구현과 같은 검사를 통과한다
        assertThat(CompanionCommentValidator.validated(comment)).isEqualTo(comment)
    }

    @Test
    fun `FR-013 실패 표지가 있으면 항상, 횟수가 있으면 글마다 그 횟수만 실패한다`() {
        repeat(3) { assertFailed(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR) { writer.write(KEY, input("[AI댓글실패] 글")) } }

        val twice = input("[AI댓글실패:2] 글")
        repeat(2) { assertFailed(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR) { writer.write("POST:2", twice) } }
        assertThat(writer.write("POST:2", twice)).isNotBlank()
        // 횟수는 글마다 센다
        assertFailed(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR) { writer.write("POST:3", twice) }
    }

    @Test
    fun `US3-AC4 욕설과 위험 표지는 올리기 전 검사가 걸러야 하는 댓글을 준다`() {
        assertThat(writer.write(KEY, input("[AI댓글욕설] 글"))).isEqualTo(FakeCompanionCommentWriter.PROFANE_COMMENT)
        assertThat(writer.write(KEY, input("[AI댓글위험] 글"))).isEqualTo(FakeCompanionCommentWriter.RISKY_COMMENT)
    }

    @Test
    fun `US3-AC5 긴 글 표지는 실제 구현처럼 규칙 검사에 걸려 실패한다`() {
        assertFailed(EmotionAnalysisFailed.Kind.INVALID_RESPONSE) { writer.write(KEY, input("[AI댓글긴글] 글")) }
    }

    @Test
    fun `표지를 요구하도록 띄우면 AI친구 표지가 있는 글에만 답한다`() {
        val optIn = FakeCompanionCommentWriter(optInOnly = true)

        assertFailed(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR) { optIn.write(KEY, input("표지가 없는 글")) }
        assertThat(optIn.write(KEY, input("[AI친구] 답을 기다리는 글"))).contains("무조건 위로해주기")
        // 다른 표지와 함께 쓸 수 있다
        assertFailed(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR) { optIn.write(KEY, input("[AI친구] [AI댓글실패] 글")) }
    }

    @Test
    fun `키가 없을 때 쓰는 구현은 본문을 보내지 않고 바로 실패한다`() {
        assertFailed(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR) {
            FakeCompanionCommentWriter.Disabled().write(KEY, input("글"))
        }
    }

    private fun input(
        content: String,
        tone: CompanionTone = CompanionTone.COMFORT_ME,
        voice: CompanionVoice = CompanionVoice.GENTLE,
    ) = CompanionCommentInput(content, tone, emotion = null, voice)

    private fun assertFailed(
        kind: EmotionAnalysisFailed.Kind,
        call: () -> Unit,
    ) {
        assertThatThrownBy { call() }
            .isInstanceOfSatisfying(CompanionCommentFailed::class.java) { assertThat(it.kind).isEqualTo(kind) }
    }

    private companion object {
        const val KEY = "POST:1"
    }
}
