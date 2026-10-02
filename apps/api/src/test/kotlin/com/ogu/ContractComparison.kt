package com.ogu

/**
 * 계약(openapi.yaml)과 실제 API(`/v3/api-docs`)의 경로, 메서드, 응답 상태 코드 집합을 비교한다.
 * 스키마 세부는 보지 않는다. `ContractTests`와 `ContractComparisonTest`(자체 점검)에서 쓴다.
 */
object ContractComparison {
    /**
     * @param expected 계약의 "METHOD path" -> 응답 상태 코드 집합
     * @param actual 실제 API의 "METHOD path" -> 응답 상태 코드 집합(`/api/v1`로 시작하는 경로만)
     * @param pendingKeys 아직 구현되지 않아 비교에서 제외할 계약 키. 실제 API에 나타나면 안 된다는
     *   보장은 하지 않는다 — 구현되면 이 집합에서 빼는 것은 호출자의 책임이다.
     * @return 실패 이유 목록. 비어 있으면 일치한다.
     */
    fun compare(
        expected: Map<String, Set<String>>,
        actual: Map<String, Set<String>>,
        pendingKeys: Set<String>,
    ): List<String> {
        val failures = mutableListOf<String>()

        expected.forEach { (key, expectedCodes) ->
            if (key in pendingKeys) return@forEach
            val actualCodes = actual[key]
            when {
                actualCodes == null ->
                    failures += "구현되었어야 할 경로가 API에 없습니다: $key"
                actualCodes != expectedCodes ->
                    failures += "$key 의 응답 상태 코드가 계약과 다릅니다: 계약=$expectedCodes, 실제=$actualCodes"
            }
        }

        actual.keys
            .filter { it !in expected.keys }
            .forEach { failures += "계약에 없는 경로가 API에 노출되어 있습니다: $it" }

        return failures
    }
}
