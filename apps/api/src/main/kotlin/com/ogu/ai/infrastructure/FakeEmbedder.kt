package com.ogu.ai.infrastructure

import com.ogu.ai.Embedder
import com.ogu.ai.Embedding
import com.ogu.ai.EmbeddingFailed
import com.ogu.ai.EmotionAnalysisFailed.Kind.UPSTREAM_ERROR
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 결정적인 가짜 임베더(007 research R10). `e2e` 프로필과 테스트 설정에서만 쓴다. 본문 어디에든 있는 표지로 방향을 정한다.
 *
 * - `[주제:이름]`이 같으면 가깝고(코사인 거리 0.01 안쪽) 이름이 다르면 멀다(1에 가깝다).
 * - `[멀기:N]`(1~9)을 함께 쓰면 같은 주제 안에서 N이 클수록 조금 더 멀다. 가까운 순서를 정할 때 쓴다.
 * - 주제 표지가 없으면 어떤 글과도 가깝지 않다.
 * - `[임베딩실패]`가 있으면 항상 실패하고, `[임베딩실패:N]`이면 글마다 처음 N번만 실패한다.
 */
class FakeEmbedder : Embedder {
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    override val model: String = MODEL

    override fun embed(
        key: String,
        content: String,
    ): Embedding {
        if (ALWAYS_FAIL in content) throw EmbeddingFailed(UPSTREAM_ERROR)
        FAIL_TIMES.find(content)?.let { match ->
            val attempt = failures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
            if (attempt <= match.groupValues[1].toInt()) throw EmbeddingFailed(UPSTREAM_ERROR)
        }
        val topic = TOPIC.find(content)?.groupValues?.get(1)
        val base = direction(topic ?: "글:$content")
        val drift =
            (
                FAR
                    .find(content)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: 0
            ) * DRIFT_STEP
        val noise = direction("흔들림:$content")
        // 같은 주제라도 글마다 조금씩 달라 순서가 정해진다
        val values = FloatArray(DIMENSIONS) { base[it] + noise[it] * (BASE_NOISE + drift) }
        return Embedding(MODEL, values)
    }

    /** [seed]로 정해지는 방향. 차원이 커서 seed가 다르면 거의 직각이다. */
    private fun direction(seed: String): FloatArray {
        val random = Random(seed.hashCode().toLong())
        return FloatArray(DIMENSIONS) { random.nextGaussian().toFloat() }
    }

    /** 키가 없을 때 쓴다. 외부로 본문을 보내지 않고 바로 실패해, 같은 감정의 글로 대신하게 된다. */
    class Disabled : Embedder {
        override val model: String = "disabled"

        override fun embed(
            key: String,
            content: String,
        ): Embedding = throw EmbeddingFailed(UPSTREAM_ERROR)
    }

    companion object {
        const val MODEL = "fake-embedder"
        const val DIMENSIONS = 2048
        private const val ALWAYS_FAIL = "[임베딩실패]"
        private const val BASE_NOISE = 0.02f
        private const val DRIFT_STEP = 0.05f
        private val FAIL_TIMES = Regex("\\[임베딩실패:(\\d{1,3})]")
        private val TOPIC = Regex("\\[주제:([^\\]]{1,20})]")
        private val FAR = Regex("\\[멀기:([1-9])]")
    }
}
