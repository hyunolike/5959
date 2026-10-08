package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.ClassifiedIntensity
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.EmotionAnalyzer
import com.ogu.ai.EmotionClassification
import com.ogu.shared.text.Grapheme
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 결정적인 가짜 분석기(research R3). `e2e` 프로필([FakeEmotionAnalyzerConfig])과 테스트 설정에서만 쓴다.
 *
 * - `[불안:높음]`처럼 감정과 강도 머리말로 시작하면 그 값을 돌려준다.
 * - `[실패]`로 시작하면 항상 실패한다.
 * - `[실패:N]`으로 시작하면 글마다 처음 N번만 실패하고, 그다음에는 머리말을 뺀 본문 길이로 정한 값을 돌려준다.
 *   시도 횟수는 글 ID별로 메모리에 센다.
 * - 그 밖에는 본문 길이로 정한 값을 돌려준다(감정은 글자 수 % 5, 강도는 100자 이하 낮음, 300자 이하 보통, 그 위 높음).
 */
class FakeEmotionAnalyzer : EmotionAnalyzer {
    private val failures = ConcurrentHashMap<Long, AtomicInteger>()

    override fun analyze(
        postId: Long,
        content: String,
    ): EmotionClassification {
        val text = content.trim()
        if (text.startsWith(ALWAYS_FAIL)) throw EmotionAnalysisFailed(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR)
        val failTimes = FAIL_TIMES.find(text)
        val preset = PRESET.find(text)
        return when {
            failTimes != null -> failThenByLength(postId, failTimes, text)
            preset != null ->
                EmotionClassification(
                    emotion = EMOTIONS.getValue(preset.groupValues[1]),
                    intensity = INTENSITIES.getValue(preset.groupValues[2]),
                    reason = "가짜 분석기: 머리말",
                )
            else -> byLength(text)
        }
    }

    private fun failThenByLength(
        postId: Long,
        match: MatchResult,
        text: String,
    ): EmotionClassification {
        val times = match.groupValues[1].toInt()
        val attempt = failures.computeIfAbsent(postId) { AtomicInteger() }.incrementAndGet()
        if (attempt <= times) throw EmotionAnalysisFailed(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR)
        return byLength(text.substring(match.range.last + 1).trim())
    }

    private fun byLength(text: String): EmotionClassification {
        val length = Grapheme.count(text)
        val intensity =
            when {
                length <= LOW_MAX_LENGTH -> ClassifiedIntensity.LOW
                length <= MEDIUM_MAX_LENGTH -> ClassifiedIntensity.MEDIUM
                else -> ClassifiedIntensity.HIGH
            }
        return EmotionClassification(
            emotion = ClassifiedEmotion.entries[length % ClassifiedEmotion.entries.size],
            intensity = intensity,
            reason = "가짜 분석기: 글자 수 $length",
        )
    }

    private companion object {
        const val ALWAYS_FAIL = "[실패]"
        const val LOW_MAX_LENGTH = 100
        const val MEDIUM_MAX_LENGTH = 300
        val FAIL_TIMES = Regex("^\\[실패:(\\d{1,3})]")
        val EMOTIONS =
            mapOf(
                "불안" to ClassifiedEmotion.ANXIETY,
                "무기력" to ClassifiedEmotion.LETHARGY,
                "외로움" to ClassifiedEmotion.LONELINESS,
                "자기비하" to ClassifiedEmotion.SELF_DEPRECATION,
                "짜증" to ClassifiedEmotion.IRRITATION,
            )
        val INTENSITIES =
            mapOf(
                "낮음" to ClassifiedIntensity.LOW,
                "보통" to ClassifiedIntensity.MEDIUM,
                "높음" to ClassifiedIntensity.HIGH,
            )
        val PRESET = Regex("^\\[(${EMOTIONS.keys.joinToString("|")}):(${INTENSITIES.keys.joinToString("|")})]")
    }
}
