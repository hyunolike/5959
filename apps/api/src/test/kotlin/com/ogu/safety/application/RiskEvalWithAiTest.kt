package com.ogu.safety.application

import com.ogu.ai.ClassifiedRisk
import com.ogu.ai.RiskClassificationFailed
import com.ogu.ai.infrastructure.SpringAiRiskClassifier
import com.ogu.safety.RiskLevel
import com.ogu.shared.config.AiProperties
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.Duration

/**
 * 키워드 규칙과 실제 AI 분류를 합친 성적을 평가용 문장 묶음으로 잰다(005 SC-005, research R15). 서비스가 하는 것과 같이
 * 둘 가운데 높은 단계를 최종 판정으로 본다.
 *
 * 실제 공급자를 200번 부르므로 평소에는 돌지 않는다. 키를 주고 켰을 때만 돈다.
 *
 * ```
 * OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*RiskEvalWithAiTest*" -i | grep EVAL
 * ```
 *
 * 문장별 결과는 `build/reports/safety-eval/ai-eval.tsv`에 남는다. 문장은 모두 검증용으로 직접 쓴 것이다.
 */
@EnabledIfEnvironmentVariable(named = "OGU_RUN_AI_EVAL", matches = "true")
class RiskEvalWithAiTest {
    @Test
    fun `SC-005 위기 문장을 놓치는 비율이 5퍼센트 이하이고 위험 없는 문장을 위기로 숨기는 비율이 10퍼센트 이하다`() {
        val terms = seedTerms()
        val rows =
            newClassifier().use { classifier ->
                evalSet().mapIndexed { index, (label, sentence) ->
                    val keyword = KeywordRule.level(sentence, terms)
                    val ai = classify(classifier, index, sentence)
                    Thread.sleep(PACE_MILLIS)
                    Row(label, sentence, keyword, ai)
                }
            }
        report(rows)

        val crisis = rows.filter { it.label == RiskLevel.CRISIS }
        val safe = rows.filter { it.label == RiskLevel.NONE }
        val concern = rows.filter { it.label == RiskLevel.CONCERN }
        val missed = crisis.filter { it.final != RiskLevel.CRISIS }
        val wronglyHidden = safe.filter { it.final == RiskLevel.CRISIS }
        val unanswered = rows.count { it.ai == null }

        val used = properties()
        val lines =
            listOf(
                "model ${used.riskModel}, max tokens ${used.riskMaxTokens}, effort ${used.riskReasoningEffort}",
                "set ${env("AI_EVAL_SET") ?: "eval-set"}, sentences ${rows.size}, AI unanswered $unanswered",
                "crisis judged CRISIS ${percent(crisis.size - missed.size, crisis.size)}",
                "crisis judged NONE ${percent(crisis.count { it.final == RiskLevel.NONE }, crisis.size)}",
                "concern judged CONCERN+ ${percent(concern.count { it.final != RiskLevel.NONE }, concern.size)}",
                "concern judged CRISIS ${percent(concern.count { it.final == RiskLevel.CRISIS }, concern.size)}",
                "safe judged CRISIS ${percent(wronglyHidden.size, safe.size)}",
                "safe judged CONCERN ${percent(safe.count { it.final == RiskLevel.CONCERN }, safe.size)}",
                "ai-only crisis judged CRISIS ${percent(crisis.count { it.ai == RiskLevel.CRISIS }, crisis.size)}",
                "ai-only safe judged CRISIS ${percent(safe.count { it.ai == RiskLevel.CRISIS }, safe.size)}",
            )
        lines.forEach { println("EVAL with-ai: $it") }
        missed.forEach { println("EVAL missed crisis (${it.final}): ${it.sentence}") }
        wronglyHidden.forEach { println("EVAL safe→crisis (keyword ${it.keyword}, ai ${it.ai}): ${it.sentence}") }

        // 답을 받지 못한 문장이 많으면 수치가 키워드 규칙만의 것이 된다
        assertThat(unanswered).describedAs("AI가 답하지 못한 문장 수").isLessThanOrEqualTo(MAX_UNANSWERED)
        assertThat(missed.size * PERCENT / crisis.size).describedAs("위기를 놓친 비율(%%)").isLessThanOrEqualTo(MAX_MISSED)
        assertThat(wronglyHidden.size * PERCENT / safe.size)
            .describedAs("위험 없는 문장을 위기로 본 비율(%%)")
            .isLessThanOrEqualTo(MAX_WRONGLY_HIDDEN)
    }

    /** 공급자의 요청 제한에 걸리면 기다렸다 다시 부른다. 끝내 답을 받지 못하면 null이다(서비스에서는 키워드 판정이 최종이 된다). */
    private fun classify(
        classifier: SpringAiRiskClassifier,
        index: Int,
        sentence: String,
    ): RiskLevel? {
        repeat(ATTEMPTS) { attempt ->
            try {
                return when (classifier.classify("EVAL:$index", sentence)) {
                    ClassifiedRisk.NONE -> RiskLevel.NONE
                    ClassifiedRisk.CONCERN -> RiskLevel.CONCERN
                    ClassifiedRisk.CRISIS -> RiskLevel.CRISIS
                }
            } catch (e: RiskClassificationFailed) {
                println("EVAL retry ${attempt + 1} for sentence $index: ${e.kind}")
                Thread.sleep(RETRY_WAIT_MILLIS * (attempt + 1))
            }
        }
        return null
    }

    private fun report(rows: List<Row>) {
        val file = File("build/reports/safety-eval/ai-eval.tsv")
        file.parentFile.mkdirs()
        val lines = rows.map { "${it.label}\t${it.keyword}\t${it.ai ?: "-"}\t${it.final}\t${it.sentence}" }
        file.writeText((listOf("label\tkeyword\tai\tfinal\tsentence") + lines).joinToString("\n") + "\n")
    }

    private fun percent(
        count: Int,
        total: Int,
    ): String = "$count/$total (${count * PERCENT_INT / total}%)"

    private fun newClassifier(): SpringAiRiskClassifier =
        SpringAiRiskClassifier.create(properties(), neverOpen(), TimeLimiter.of(TIME_LIMIT))

    /** 모델, 토큰 상한, 추론 정도를 환경 변수로 바꿔 가며 잴 수 있다. 주지 않으면 서비스의 기본값이다. */
    private fun properties(): AiProperties {
        val defaults = AiProperties()
        return defaults.copy(
            apiKey = System.getenv("AI_API_KEY").orEmpty(),
            riskModel = env("AI_RISK_MODEL") ?: defaults.riskModel,
            riskMaxTokens = env("AI_RISK_MAX_TOKENS")?.toIntOrNull() ?: defaults.riskMaxTokens,
            riskReasoningEffort = System.getenv("AI_RISK_REASONING_EFFORT") ?: defaults.riskReasoningEffort,
            riskTimeout = Duration.ofSeconds(60),
        )
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    /** 평가에서는 연달아 실패해도 계속 불러야 한다. 열리지 않는 서킷 브레이커를 쓴다. */
    private fun neverOpen(): CircuitBreaker =
        CircuitBreaker.of(
            "riskEval",
            CircuitBreakerConfig
                .custom()
                .minimumNumberOfCalls(SLIDING_WINDOW)
                .slidingWindowSize(SLIDING_WINDOW)
                .build(),
        )

    private fun evalSet(): List<Pair<RiskLevel, String>> =
        resource(env("AI_EVAL_SET") ?: "/safety/eval-set.tsv")
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (label, _, sentence) = line.split("\t", limit = 3)
                RiskLevel.valueOf(label) to sentence
            }

    private fun seedTerms(): SafetyTerms {
        val byKind =
            SEED_ROW
                .findAll(resource("/db/migration/V5__safety.sql"))
                .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
        return SafetyTerms(
            crisis = byKind["CRISIS"].orEmpty().toSet(),
            concern = byKind["CONCERN"].orEmpty().toSet(),
        )
    }

    private fun resource(path: String): String {
        val url = requireNotNull(javaClass.getResource(path)) { "리소스가 없습니다: $path" }
        return url.readText()
    }

    private data class Row(
        val label: RiskLevel,
        val sentence: String,
        val keyword: RiskLevel,
        val ai: RiskLevel?,
    ) {
        /** 서비스의 최종 판정: 키워드와 AI 가운데 높은 쪽. AI가 답하지 못했으면 키워드 판정이다. */
        val final: RiskLevel get() = ai?.let(keyword::max) ?: keyword
    }

    private companion object {
        val SEED_ROW = Regex("""\('(CRISIS|CONCERN|PROFANITY|ALLOW)', '([^']+)'\)""")
        val TIME_LIMIT: TimeLimiterConfig = TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(60)).build()
        const val ATTEMPTS = 4
        const val PACE_MILLIS = 500L
        const val RETRY_WAIT_MILLIS = 5_000L
        const val SLIDING_WINDOW = 100_000
        const val MAX_UNANSWERED = 4
        const val MAX_MISSED = 5.0
        const val MAX_WRONGLY_HIDDEN = 10.0
        const val PERCENT = 100.0
        const val PERCENT_INT = 100
    }
}
