package com.ogu.support

import com.ogu.ai.EmotionAnalyzer
import com.ogu.ai.EmotionClassification
import com.ogu.ai.infrastructure.EmotionResponseParser
import com.ogu.ai.infrastructure.FakeEmotionAnalyzer
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

    override fun analyze(
        postId: Long,
        content: String,
    ): EmotionClassification {
        calls.computeIfAbsent(postId) { AtomicInteger() }.incrementAndGet()
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

    fun clearRaw(postId: Long) {
        rawResponses.remove(postId)
    }
}
