package com.ogu.safety.application

import com.ogu.safety.RiskLevel

/** 종류별 낱말 목록. 모두 [TextNormalizer.termOf]로 다듬은 꼴이다. */
data class SafetyTerms(
    val crisis: Set<String>,
    val concern: Set<String>,
    val profanity: Set<String> = emptySet(),
    val allow: Set<String> = emptySet(),
) {
    companion object {
        /**
         * DB에서 목록을 한 번도 읽지 못했을 때 쓰는 최소 목록(005 research R2). 원본(WEBBB)의 위기 표현 여섯 개다.
         * 목록을 읽지 못해도 감지가 꺼지지 않게 한다(constitution IV).
         */
        val BUILT_IN =
            SafetyTerms(
                crisis = setOf("죽고싶", "자살", "자해", "스스로목숨", "삶을끝", "죽어버리고싶"),
                concern = emptySet(),
            )
    }
}

/**
 * 키워드 규칙(005 research R4). AI 없이 메모리 안에서만 판정한다. 위기 목록에 걸리면 [RiskLevel.CRISIS], 아니고 우려 목록에
 * 걸리면 [RiskLevel.CONCERN]이다. 부정문이나 인용은 가리지 않는다. 놓치는 것보다 잘못 숨기는 쪽을 택했고, 잘못 숨긴 것은
 * 재검토 요청과 운영자가 푼다.
 */
object KeywordRule {
    fun level(
        content: String,
        terms: SafetyTerms,
    ): RiskLevel {
        val text = TextNormalizer.normalize(content).text
        return when {
            terms.crisis.any { it in text } -> RiskLevel.CRISIS
            terms.concern.any { it in text } -> RiskLevel.CONCERN
            else -> RiskLevel.NONE
        }
    }
}
