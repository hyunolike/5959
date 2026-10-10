package com.ogu.ai.infrastructure

import com.ogu.shared.config.AiProperties
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.sqrt

/**
 * 실제 공급자와 기본 임베딩 모델이 답하는지 본다(007 research R2). 모델을 바꿨을 때 돌린다. 가짜 임베더를 쓰는 다른
 * 테스트는 공급자가 모델을 내려도 알아채지 못한다.
 *
 * ```
 * OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*EmbedderLiveTest*" -i | grep LIVE
 * ```
 */
@EnabledIfEnvironmentVariable(named = "OGU_RUN_AI_EVAL", matches = "true")
class EmbedderLiveTest {
    @Test
    fun `기본 모델이 2048차원을 돌려주고 비슷한 고민이 무관한 문장보다 가깝다`() {
        val properties = AiProperties(apiKey = System.getenv("AI_API_KEY").orEmpty())
        val embedder = HttpEmbedder(properties, CircuitBreaker.ofDefaults("embedderLive"))

        // 검증용으로 지어낸 문장이다
        val a = embedder.embed("LIVE:1", "팀장님이 보고서를 세 번이나 돌려보내서 자신감이 없어졌어요")
        val b = embedder.embed("LIVE:2", "상사가 제 기획안을 계속 반려해서 위축돼요")
        val c = embedder.embed("LIVE:3", "점심 메뉴 고르는 게 제일 어려워요")

        val similar = cosine(a.values, b.values)
        val unrelated = cosine(a.values, c.values)
        println("LIVE embedding: model ${a.model}, dimensions ${a.values.size}, similar $similar, unrelated $unrelated")
        assertThat(a.model).isEqualTo(properties.embeddingModel)
        assertThat(a.values).hasSize(properties.embeddingDimensions)
        assertThat(similar).isGreaterThan(unrelated)
    }

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        val dot = a.indices.sumOf { (a[it] * b[it]).toDouble() }
        val normA = sqrt(a.sumOf { (it * it).toDouble() })
        val normB = sqrt(b.sumOf { (it * it).toDouble() })
        return dot / (normA * normB)
    }
}
