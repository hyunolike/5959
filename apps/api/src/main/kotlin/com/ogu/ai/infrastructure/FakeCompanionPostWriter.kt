package com.ogu.ai.infrastructure

import com.ogu.ai.CompanionPostFailed
import com.ogu.ai.CompanionPostInput
import com.ogu.ai.CompanionPostWriter
import com.ogu.ai.EmotionAnalysisFailed.Kind.UPSTREAM_ERROR
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 결정적인 가짜 글쓰기(012 research R15). `e2e` 프로필과 테스트 설정에서만 쓴다. 소재의 설명 어디에든 있는 표지로 결과를
 * 정한다. 운영의 소재 목록에는 표지가 없으므로, 표지는 테스트가 소재를 정해 줄 때만 쓰인다.
 *
 * - `[AI글실패]`가 있으면 항상 실패한다. `[AI글실패:N]`이 있으면 기록마다 처음 N번만 실패한다.
 * - `[AI글욕설]`이면 욕설 목록의 낱말이 든 글, `[AI글위험]`이면 가짜 위험 분류기가 우려로 보는 글, `[AI글긴글]`이면
 *   500자를 넘는 글을 준다. 긴 글은 실제 구현처럼 여기서 규칙 검사에 걸려 실패한다.
 * - 그 밖에는 소재와 말씨와 말투의 이름이 든 정해진 글을 준다. 감정 분석이 끝나지 않게 `[실패]`로 시작한다. 가짜 감정
 *   분석기가 그 표지를 보고 실패해 몬스터가 생기지 않는다(다른 테스트의 수치가 흔들리지 않는다).
 */
class FakeCompanionPostWriter : CompanionPostWriter {
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    override fun write(
        key: String,
        input: CompanionPostInput,
    ): String {
        val topic = input.topic
        if (fails(key, topic)) throw CompanionPostFailed(UPSTREAM_ERROR)
        val answer =
            when {
                PROFANE in topic -> PROFANE_POST
                RISKY in topic -> RISKY_POST
                TOO_LONG in topic -> "가".repeat(CompanionPostValidator.MAX_LENGTH + 1)
                else -> postOf(input)
            }
        return CompanionPostValidator.validated(answer)
    }

    private fun fails(
        key: String,
        topic: String,
    ): Boolean {
        val failTimes = FAIL_TIMES.find(topic)?.let { it.groupValues[1].toInt() } ?: 0
        // 횟수 표지가 있을 때만 센다. 기록마다 따로 센다
        val attempt = if (failTimes > 0) failures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet() else 0
        return ALWAYS_FAIL in topic || attempt in 1..failTimes
    }

    /** 키가 없을 때 쓴다. 외부로 아무것도 보내지 않고 바로 실패해, AI 친구는 글을 쓰지 않는다. */
    class Disabled : CompanionPostWriter {
        override fun write(
            key: String,
            input: CompanionPostInput,
        ): String = throw CompanionPostFailed(UPSTREAM_ERROR)
    }

    companion object {
        private const val ALWAYS_FAIL = "[AI글실패]"
        private const val PROFANE = "[AI글욕설]"
        private const val RISKY = "[AI글위험]"
        private const val TOO_LONG = "[AI글긴글]"
        private val FAIL_TIMES = Regex("\\[AI글실패:(\\d{1,3})]")
        private val MARKERS = Regex("\\[AI글[^\\]]*]\\s*")

        /** V5 시드의 욕설 낱말이 들어 있다. 올리기 전 검사가 걸러야 한다. */
        const val PROFANE_POST = "[실패] 오늘 회의에서 들은 말이 정말 지랄 같았어요. 아직도 마음이 가라앉지 않아요."

        /** 가짜 위험 분류기(`FakeRiskClassifier`)가 우려로 보는 표지가 들어 있다. 키워드 목록에는 걸리지 않는다. */
        const val RISKY_POST = "[실패] [우려] 요즘은 출근길이 유난히 길게 느껴져요. 어디에 말해야 할지 모르겠어요."

        fun postOf(input: CompanionPostInput): String {
            val topic = input.topic.replace(MARKERS, "").trim()
            val asked = "${input.tone.label} 말투의 댓글을 바라요."
            return "[실패] ${input.voice.label}가 지어 쓴 이야기예요. $topic $asked"
        }
    }
}
