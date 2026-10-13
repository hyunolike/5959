package com.ogu.ai.infrastructure

import com.ogu.ai.CompanionCommentFailed
import com.ogu.ai.CompanionCommentInput
import com.ogu.ai.CompanionCommentWriter
import com.ogu.ai.CompanionVoice
import com.ogu.ai.EmotionAnalysisFailed.Kind.UPSTREAM_ERROR
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 결정적인 가짜 댓글 쓰기(009 research R11). `e2e` 프로필과 테스트 설정에서만 쓴다. 글의 본문 어디에든 있는 표지로 결과를
 * 정한다(다른 가짜 분석기의 표지와 함께 쓸 수 있다).
 *
 * - `[AI댓글실패]`가 있으면 항상 실패한다.
 * - `[AI댓글실패:N]`이 있으면 글마다 처음 N번만 실패한다.
 * - `[AI댓글욕설]`이면 욕설 목록의 낱말이 든 댓글, `[AI댓글위험]`이면 가짜 위험 분류기가 우려로 보는 댓글,
 *   `[AI댓글긴글]`이면 300자를 넘는 댓글을 준다. 긴 댓글은 실제 구현처럼 여기서 규칙 검사에 걸려 실패한다.
 * - `[AI댓글되풀이]`가 있고 앞서 남긴 AI 친구의 댓글이 있으면 그 댓글을 그대로 준다(012). 되풀이 검사가 걸러야 한다.
 * - 그 밖에는 말투와 말씨의 이름이 든 정해진 댓글을 준다. 친구마다 문장이 다르다.
 *
 * [optInOnly]로 띄우면 본문에 `[AI친구]`(또는 `[AI친구:2]`처럼 수를 붙인 것)가 있는 글에만 답하고 나머지는 실패한다. `e2e` 프로필이 그렇게 쓴다. 기능을 켠 채
 * 도는 e2e에서 AI 친구가 모든 글에 댓글과 공감을 남기면 다른 시나리오의 댓글 수와 몬스터 HP가 흔들린다.
 */
class FakeCompanionCommentWriter(
    private val optInOnly: Boolean = false,
) : CompanionCommentWriter {
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    override fun write(
        key: String,
        input: CompanionCommentInput,
    ): String {
        val content = input.content
        if (fails(key, content)) throw CompanionCommentFailed(UPSTREAM_ERROR)
        val answer =
            when {
                PROFANE in content -> PROFANE_COMMENT
                RISKY in content -> RISKY_COMMENT
                TOO_LONG in content -> "가".repeat(CompanionCommentValidator.MAX_LENGTH + 1)
                REPEAT in content && input.previous.isNotEmpty() -> input.previous.first()
                else -> commentOf(input)
            }
        return CompanionCommentValidator.validated(answer)
    }

    private fun fails(
        key: String,
        content: String,
    ): Boolean {
        val notAsked = optInOnly && !OPT_IN.containsMatchIn(content)
        val failTimes = FAIL_TIMES.find(content)?.let { it.groupValues[1].toInt() } ?: 0
        // 횟수 표지가 있을 때만 센다. 글마다 따로 센다
        val attempt = if (failTimes > 0) failures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet() else 0
        return notAsked || ALWAYS_FAIL in content || attempt in 1..failTimes
    }

    /** 키가 없을 때 쓴다. 외부로 본문을 보내지 않고 바로 실패해, AI 친구는 아무 댓글도 남기지 않는다. */
    class Disabled : CompanionCommentWriter {
        override fun write(
            key: String,
            input: CompanionCommentInput,
        ): String = throw CompanionCommentFailed(UPSTREAM_ERROR)
    }

    companion object {
        private val OPT_IN = Regex("\\[AI친구(:[1-3])?]")
        private const val REPEAT = "[AI댓글되풀이]"
        private const val ALWAYS_FAIL = "[AI댓글실패]"
        private const val PROFANE = "[AI댓글욕설]"
        private const val RISKY = "[AI댓글위험]"
        private const val TOO_LONG = "[AI댓글긴글]"
        private val FAIL_TIMES = Regex("\\[AI댓글실패:(\\d{1,3})]")

        /** V5 시드의 욕설 낱말이 들어 있다. 올리기 전 검사가 걸러야 한다. */
        const val PROFANE_COMMENT = "그런 말을 듣다니 정말 지랄 같은 하루였겠어요."

        /** 가짜 위험 분류기(`FakeRiskClassifier`)가 우려로 보는 표지가 들어 있다. 키워드 목록에는 걸리지 않는다. */
        const val RISKY_COMMENT = "[우려] 오늘은 푹 쉬어도 괜찮아요."

        /** 친구마다 맺음말이 다르다. 같은 글에 여러 친구가 답해도 되풀이 검사에 걸리지 않는다. */
        private val CLOSINGS =
            mapOf(
                CompanionVoice.GENTLE to "오늘도 정말 애썼어요.",
                CompanionVoice.HEARTY to "그만하면 충분히 잘 해냈어요.",
                CompanionVoice.CALM to "잠깐 숨을 골라도 괜찮아요.",
            )

        fun commentOf(input: CompanionCommentInput): String {
            val who = "${input.voice.label}가 ${input.tone.label} 말투로 전해요."
            return "$who ${CLOSINGS.getValue(input.voice)}"
        }
    }
}
