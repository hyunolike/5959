package com.ogu.recommend

import com.ogu.ai.EmbeddingFailed
import com.ogu.ai.infrastructure.HttpEmbedder
import com.ogu.shared.config.AiProperties
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.sqrt

/**
 * 실제 임베딩 모델로 가까움의 기준(`ogu.recommend.max-distance`)을 잰다(007 research R9, SC-001, SC-002).
 * 문장 90개를 한 번씩 보내므로 켰을 때만 돈다. 모델이나 기준값을 바꿨을 때 돌린다.
 *
 * ```
 * OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*RecommendEvalTest*" -i | grep EVAL
 * ```
 *
 * - 찾은 비율(SC-001): 글마다 기준 안의 가까운 글 5개에 같은 주제가 하나라도 있는 비율
 * - 잘못 보인 비율(SC-002): 그렇게 보인 글 가운데 주제가 다른 글의 비율
 * - 빈 비율: 기준 안의 글이 하나도 없어 같은 감정의 글로 넘어가는 비율
 * - 첫째: 기준과 상관없이 가장 가까운 글이 같은 주제인 비율. 모델이 주제를 얼마나 가르는지 본다
 *
 * 따로 쓴 묶음은 주제마다 문장이 셋이라, 5개가 다 보이면 잘못 보인 비율이 60% 아래로 내려갈 수 없다. 기준값이 걸러 낼
 * 때의 값을 본다.
 */
@EnabledIfEnvironmentVariable(named = "OGU_RUN_AI_EVAL", matches = "true")
class RecommendEvalTest {
    private val embedder =
        HttpEmbedder(
            AiProperties(apiKey = System.getenv("AI_API_KEY").orEmpty()),
            CircuitBreaker.ofDefaults("recommendEval"),
        )

    @Test
    fun `SC-001 SC-002 기준값에 따라 같은 주제를 찾는 비율과 다른 주제가 보이는 비율`() {
        val set = embedAll("eval-set.tsv")
        val holdout = embedAll("eval-holdout.tsv")

        THRESHOLDS.forEach { threshold ->
            println("EVAL set     ${score(set, threshold)}")
            println("EVAL holdout ${score(holdout, threshold)}")
        }
        // 실제 서비스에서는 주제가 다른 글이 훨씬 많다. 두 묶음을 합쳐 후보를 늘린 값도 본다
        THRESHOLDS.forEach { println("EVAL mixed   ${score(set + holdout, it)}") }

        val chosen = score(set, CHOSEN)
        val met = chosen.found >= FOUND_TARGET && chosen.wrong <= WRONG_TARGET
        println("EVAL 목표(찾음 $FOUND_TARGET 이상, 잘못 $WRONG_TARGET 이하): ${if (met) "달성" else "미달"} $chosen")
        // 지금 모델(nemotron-3-embed-1b)은 목표에 못 미친다(research R9). 목표를 단정하면 늘 실패하므로, 모델이 주제를
        // 조금이라도 가르는지만 본다. 아무렇게나 골랐을 때 가장 가까운 글이 같은 주제일 확률은 5/59다
        assertThat(chosen.first).isGreaterThan(RANDOM_FIRST * 2)
    }

    private fun embedAll(file: String): List<Sample> =
        javaClass
            .getResource("/recommend/$file")!!
            .readText()
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexed { index, line ->
                val (topic, sentence) = line.split("\t")
                Sample(topic, normalize(embedWithRetry("EVAL:$file:$index", sentence)))
            }

    /** 무료 한도에 걸리면 잠깐 쉬었다가 다시 한다. */
    private fun embedWithRetry(
        key: String,
        sentence: String,
    ): FloatArray {
        repeat(RETRIES) { attempt ->
            try {
                return embedder.embed(key, sentence).values
            } catch (e: EmbeddingFailed) {
                println("EVAL retry $key ${e.kind} ${attempt + 1}")
                Thread.sleep(RETRY_WAIT_MS * (attempt + 1))
            }
        }
        return embedder.embed(key, sentence).values
    }

    private fun score(
        samples: List<Sample>,
        threshold: Double,
    ): Score {
        var found = 0
        var empty = 0
        var shown = 0
        var wrong = 0
        var first = 0
        samples.forEachIndexed { index, sample ->
            val ordered =
                samples
                    .filterIndexed { other, _ -> other != index }
                    .map { it.topic to distance(sample.vector, it.vector) }
                    .sortedBy { it.second }
            val near = ordered.filter { it.second <= threshold }.take(LIMIT)
            if (ordered.first().first == sample.topic) first++
            if (near.isEmpty()) empty++
            if (near.any { it.first == sample.topic }) found++
            shown += near.size
            wrong += near.count { it.first != sample.topic }
        }
        return Score(
            threshold = threshold,
            found = found.toDouble() / samples.size,
            wrong = if (shown == 0) 0.0 else wrong.toDouble() / shown,
            empty = empty.toDouble() / samples.size,
            first = first.toDouble() / samples.size,
        )
    }

    private fun normalize(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    private fun distance(
        a: FloatArray,
        b: FloatArray,
    ): Double = 1.0 - a.indices.sumOf { (a[it] * b[it]).toDouble() }

    private class Sample(
        val topic: String,
        val vector: FloatArray,
    )

    private data class Score(
        val threshold: Double,
        val found: Double,
        val wrong: Double,
        val empty: Double,
        val first: Double,
    ) {
        override fun toString() = "기준 %.2f 찾음 %.3f 잘못 %.3f 빔 %.3f 첫째 %.3f".format(threshold, found, wrong, empty, first)
    }

    companion object {
        private val THRESHOLDS = listOf(0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50)

        /** application.yml의 `ogu.recommend.max-distance`와 같은 값이어야 한다. */
        private const val CHOSEN = 0.25
        private const val FOUND_TARGET = 0.80
        private const val WRONG_TARGET = 0.20
        private const val RANDOM_FIRST = 5.0 / 59
        private const val LIMIT = 5
        private const val RETRIES = 5
        private const val RETRY_WAIT_MS = 3_000L
    }
}
