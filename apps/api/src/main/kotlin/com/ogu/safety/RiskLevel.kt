package com.ogu.safety

/**
 * 위험 단계(005 spec). 순서가 곧 높낮이다. [CONCERN]은 숨기지 않고 안내만 하고, [CRISIS]는 숨기고 안내한다.
 * 키워드 규칙과 AI 분류 가운데 더 높은 쪽을 따른다([max]).
 */
enum class RiskLevel {
    NONE,
    CONCERN,
    CRISIS,
    ;

    fun max(other: RiskLevel): RiskLevel = if (other > this) other else this

    companion object {
        fun of(name: String): RiskLevel = entries.firstOrNull { it.name == name } ?: NONE
    }
}
