package com.ogu.safety.application

import com.ogu.safety.RiskLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * T052: 키워드 규칙을 평가용 문장 묶음으로 잰다(005 research R15, SC-005). 낱말은 V5 시드를 그대로 읽는다.
 *
 * 키워드 규칙만으로는 목록에 없는 표현을 잡지 못한다. 그것은 AI 분류의 몫이라 재현율 목표(95%)는 여기서 단언하지 않고
 * 수치만 `EVAL`로 출력한다. 단언은 둘이다. 목록에 있는 표현으로 쓴 문장(K)은 모두 그 단계로 잡는다. 위험 없는 문장을
 * 위기로 보는 비율은 10% 이하다.
 */
class KeywordRuleEvalTest {
    private val terms = seedTerms()
    private val rows = evalSet()

    @Test
    fun `평가 묶음은 위기 50, 우려 50, 위험 없음 100문장이다`() {
        assertThat(rows.groupingBy { it.label }.eachCount())
            .containsEntry(RiskLevel.CRISIS, 50)
            .containsEntry(RiskLevel.CONCERN, 50)
            .containsEntry(RiskLevel.NONE, 100)
    }

    @Test
    fun `목록에 있는 표현은 모두 잡는다`() {
        val missed = rows.filter { it.byKeyword && it.judged != it.label }

        assertThat(missed.map { "${it.label}:${it.sentence}" }).isEmpty()
    }

    @Test
    fun `위험 없는 문장을 위기로 보는 비율이 10퍼센트 이하다`() {
        val safe = rows.filter { it.label == RiskLevel.NONE }
        val falseCrisis = safe.filter { it.judged == RiskLevel.CRISIS }
        val falseConcern = safe.filter { it.judged == RiskLevel.CONCERN }
        val crisis = rows.filter { it.label == RiskLevel.CRISIS }
        val concern = rows.filter { it.label == RiskLevel.CONCERN }

        val crisisRecall = percent(crisis.count { it.judged == RiskLevel.CRISIS }, crisis.size)
        val concernRecall = percent(concern.count { it.judged != RiskLevel.NONE }, concern.size)
        println("EVAL keyword-only: crisis recall $crisisRecall, concern-or-higher recall $concernRecall")
        println("EVAL keyword-only: safe→crisis ${percent(falseCrisis.size, safe.size)}")
        println("EVAL keyword-only: safe→concern ${percent(falseConcern.size, safe.size)}")
        falseCrisis.forEach { println("EVAL safe→crisis: ${it.sentence}") }

        assertThat(falseCrisis.size * 100.0 / safe.size).isLessThanOrEqualTo(MAX_FALSE_CRISIS_PERCENT)
    }

    private fun percent(
        count: Int,
        total: Int,
    ): String = "$count/$total (${count * 100 / total}%)"

    private fun evalSet(): List<EvalRow> =
        resource("/safety/eval-set.tsv")
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (label, by, sentence) = line.split("\t", limit = 3)
                EvalRow(RiskLevel.valueOf(label), by == "K", sentence, KeywordRule.level(sentence, terms))
            }

    /** V5 마이그레이션의 낱말 시드. 테스트가 목록을 따로 들고 있으면 시드와 어긋난다. */
    private fun seedTerms(): SafetyTerms {
        val byKind =
            SEED_ROW
                .findAll(resource("/db/migration/V5__safety.sql"))
                .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
        return SafetyTerms(
            crisis = byKind["CRISIS"].orEmpty().toSet(),
            concern = byKind["CONCERN"].orEmpty().toSet(),
            profanity = byKind["PROFANITY"].orEmpty().toSet(),
            allow = byKind["ALLOW"].orEmpty().toSet(),
        )
    }

    private fun resource(path: String): String {
        val url = requireNotNull(javaClass.getResource(path)) { "리소스가 없습니다: $path" }
        return url.readText()
    }

    private data class EvalRow(
        val label: RiskLevel,
        val byKeyword: Boolean,
        val sentence: String,
        val judged: RiskLevel,
    )

    private companion object {
        val SEED_ROW = Regex("""\('(CRISIS|CONCERN|PROFANITY|ALLOW)', '([^']+)'\)""")
        const val MAX_FALSE_CRISIS_PERCENT = 10.0
    }
}
