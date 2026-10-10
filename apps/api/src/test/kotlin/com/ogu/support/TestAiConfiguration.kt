package com.ogu.support

import com.ogu.ai.ClassifiedRisk
import com.ogu.ai.Embedder
import com.ogu.ai.EmotionAnalyzer
import com.ogu.ai.EmotionClassification
import com.ogu.ai.RiskClassifier
import com.ogu.ai.WeeklyLetterInput
import com.ogu.ai.WeeklyLetterWriter
import com.ogu.ai.infrastructure.EmotionResponseParser
import com.ogu.ai.infrastructure.FakeEmbedder
import com.ogu.ai.infrastructure.FakeEmotionAnalyzer
import com.ogu.ai.infrastructure.FakeRiskClassifier
import com.ogu.ai.infrastructure.FakeWeeklyLetterWriter
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 모든 Spring 테스트가 실제 LLM 대신 가짜 분석기(research R3)를 쓰게 한다. [TestcontainersConfiguration]이 가져온다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestAiConfiguration {
    @Bean
    @Primary
    fun scriptedEmotionAnalyzer(): ScriptedEmotionAnalyzer = ScriptedEmotionAnalyzer(FakeEmotionAnalyzer())

    /** 위험 분류도 가짜를 쓴다(005 research R3). 본문의 `[위기]`, `[우려]`, `[위험분류실패:N]` 표지로 결과를 정한다. */
    @Bean
    @Primary
    fun scriptedRiskClassifier(): ScriptedRiskClassifier = ScriptedRiskClassifier(FakeRiskClassifier())

    /** 임베딩도 가짜를 쓴다(007 research R10). 본문의 `[주제:이름]`, `[임베딩실패:N]` 표지로 결과를 정한다. */
    @Bean
    @Primary
    fun fakeEmbedder(): Embedder = FakeEmbedder()

    /** 편지도 가짜가 쓴다(008 research R7). 수치로 결과가 정해진다. */
    @Bean
    @Primary
    fun scriptedWeeklyLetterWriter(): ScriptedWeeklyLetterWriter = ScriptedWeeklyLetterWriter(FakeWeeklyLetterWriter())
}

/**
 * [FakeWeeklyLetterWriter]에 테스트용 손잡이를 단 것. 리포트마다 받은 수치와 호출 횟수를 기억하고, [answers]에 쓴 글 수로
 * 답을 정해 두면 그 답을 그대로 돌려준다(규칙에 맞지 않는 답을 흉내 낼 때 쓴다).
 */
class ScriptedWeeklyLetterWriter(
    private val delegate: WeeklyLetterWriter,
) : WeeklyLetterWriter {
    val inputs = ConcurrentHashMap<String, WeeklyLetterInput>()
    val answers = ConcurrentHashMap<Int, String>()
    private val calls = ConcurrentHashMap<String, AtomicInteger>()

    override fun write(
        key: String,
        input: WeeklyLetterInput,
    ): String {
        inputs[key] = input
        calls.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        return answers[input.postCount] ?: delegate.write(key, input)
    }

    fun callsOf(reportId: Long): Int = calls["REPORT:$reportId"]?.get() ?: 0

    fun inputOf(reportId: Long): WeeklyLetterInput? = inputs["REPORT:$reportId"]
}

/**
 * [FakeRiskClassifier]에 테스트용 손잡이를 단 분류기. 대상마다 호출 횟수를 세고, [beforeAnswer]로 답하기 직전에 일을
 * 끼워 넣는다(분류하는 사이에 글이 고쳐지는 경우 흉내).
 */
class ScriptedRiskClassifier(
    private val delegate: RiskClassifier,
) : RiskClassifier {
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    private val beforeAnswer = ConcurrentHashMap<String, () -> Unit>()

    override fun classify(
        key: String,
        content: String,
    ): ClassifiedRisk {
        calls.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        beforeAnswer.remove(key)?.invoke()
        return delegate.classify(key, content)
    }

    fun callsFor(key: String): Int = calls[key]?.get() ?: 0

    /** 이 대상을 다음에 분류할 때 답하기 직전에 [action]을 한 번 실행한다. */
    fun beforeAnswer(
        key: String,
        action: () -> Unit,
    ) {
        beforeAnswer[key] = action
    }
}

/**
 * [FakeEmotionAnalyzer]에 테스트용 손잡이를 단 분석기. 글마다 호출 횟수를 세고, [respondRaw]로 정한 글은 모델이 준 원문
 * 응답처럼 실제 파서([EmotionResponseParser])에 넘긴다(목록에 없는 감정, 잘못된 JSON).
 */
class ScriptedEmotionAnalyzer(
    private val delegate: EmotionAnalyzer,
) : EmotionAnalyzer {
    private val calls = ConcurrentHashMap<Long, AtomicInteger>()
    private val rawResponses = ConcurrentHashMap<Long, String>()
    private val beforeAnswer = ConcurrentHashMap<Long, () -> Unit>()

    override fun analyze(
        postId: Long,
        content: String,
    ): EmotionClassification {
        calls.computeIfAbsent(postId) { AtomicInteger() }.incrementAndGet()
        beforeAnswer.remove(postId)?.invoke()
        val raw = rawResponses[postId] ?: return delegate.analyze(postId, content)
        return EmotionResponseParser.parse(raw)
    }

    fun callsFor(postId: Long): Int = calls[postId]?.get() ?: 0

    fun respondRaw(
        postId: Long,
        raw: String,
    ) {
        rawResponses[postId] = raw
    }

    /** 이 글을 다음에 분석할 때 답하기 직전에 [action]을 한 번 실행한다(호출 중에 일어나는 일 흉내). */
    fun beforeAnswer(
        postId: Long,
        action: () -> Unit,
    ) {
        beforeAnswer[postId] = action
    }

    fun clearRaw(postId: Long) {
        rawResponses.remove(postId)
    }
}
