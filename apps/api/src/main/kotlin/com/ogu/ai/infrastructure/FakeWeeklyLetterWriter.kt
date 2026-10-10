package com.ogu.ai.infrastructure

import com.ogu.ai.EmotionAnalysisFailed.Kind.UPSTREAM_ERROR
import com.ogu.ai.WeeklyLetterFailed
import com.ogu.ai.WeeklyLetterInput
import com.ogu.ai.WeeklyLetterWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 결정적인 가짜 편지 쓰기(008 research R7). `e2e` 프로필과 테스트 설정에서만 쓴다. 본문을 받지 않으므로 수치로 결과를
 * 정한다.
 *
 * - 받은 댓글 수가 [ALWAYS_FAIL_COMMENTS]면 항상 실패한다.
 * - 쓴 글 수가 [FAIL_TWICE_POSTS]면 리포트마다 처음 두 번만 실패한다.
 * - 그 밖에는 수치로 정해지는 편지를 돌려준다.
 */
class FakeWeeklyLetterWriter : WeeklyLetterWriter {
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    override fun write(
        key: String,
        input: WeeklyLetterInput,
    ): String {
        if (input.receivedComments == ALWAYS_FAIL_COMMENTS) throw WeeklyLetterFailed(UPSTREAM_ERROR)
        if (input.postCount == FAIL_TWICE_POSTS) {
            val attempt = failures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
            if (attempt <= FAIL_TIMES) throw WeeklyLetterFailed(UPSTREAM_ERROR)
        }
        return letterOf(input)
    }

    /** 키가 없을 때 쓴다. 외부로 보내지 않고 바로 실패해, 리포트는 편지 없이 닫힌다. */
    class Disabled : WeeklyLetterWriter {
        override fun write(
            key: String,
            input: WeeklyLetterInput,
        ): String = throw WeeklyLetterFailed(UPSTREAM_ERROR)
    }

    companion object {
        const val ALWAYS_FAIL_COMMENTS = 13
        const val FAIL_TWICE_POSTS = 7
        private const val FAIL_TIMES = 2

        fun letterOf(input: WeeklyLetterInput): String =
            "지난주에 글을 ${input.postCount}개 쓰셨어요. 공감 ${input.receivedLikes}개와 댓글 ${input.receivedComments}개가 곁에 있었어요."
    }
}
