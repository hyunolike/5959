package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedRisk
import com.ogu.ai.EmotionAnalysisFailed.Kind.UPSTREAM_ERROR
import com.ogu.ai.RiskClassificationFailed
import com.ogu.ai.RiskClassifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 결정적인 가짜 위험 분류기(005 research R3). `e2e` 프로필과 테스트 설정에서만 쓴다. 본문 어디에든 있는 표지로 결과를
 * 정한다(감정 분석기의 머리말 뒤에 붙여 쓸 수 있다).
 *
 * - `[위험분류실패]`가 있으면 항상 실패한다.
 * - `[위험분류실패:N]`이 있으면 대상마다 처음 N번만 실패하고 그다음에는 나머지 표지로 정한다.
 * - `[위기]`가 있으면 위기, `[우려]`가 있으면 우려, 없으면 위험 없음이다. 키워드 목록과 상관없이 정해지므로 "목록에 없는
 *   표현을 AI만 알아본" 경우를 만들 수 있다.
 */
class FakeRiskClassifier : RiskClassifier {
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    override fun classify(
        key: String,
        content: String,
    ): ClassifiedRisk {
        if (ALWAYS_FAIL in content) throw RiskClassificationFailed(UPSTREAM_ERROR)
        FAIL_TIMES.find(content)?.let { match ->
            val attempt = failures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
            if (attempt <= match.groupValues[1].toInt()) throw RiskClassificationFailed(UPSTREAM_ERROR)
        }
        return when {
            CRISIS in content -> ClassifiedRisk.CRISIS
            CONCERN in content -> ClassifiedRisk.CONCERN
            else -> ClassifiedRisk.NONE
        }
    }

    /** 키가 없을 때 쓴다. 외부로 본문을 보내지 않고 바로 실패해, 키워드 규칙의 판정이 최종이 된다. */
    class Disabled : RiskClassifier {
        override fun classify(
            key: String,
            content: String,
        ): ClassifiedRisk = throw RiskClassificationFailed(UPSTREAM_ERROR)
    }

    private companion object {
        const val ALWAYS_FAIL = "[위험분류실패]"
        const val CRISIS = "[위기]"
        const val CONCERN = "[우려]"
        val FAIL_TIMES = Regex("\\[위험분류실패:(\\d{1,3})]")
    }
}
