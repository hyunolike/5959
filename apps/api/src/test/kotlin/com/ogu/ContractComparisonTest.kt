package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ContractTests가 실제로 계약 불일치를 잡아내는지에 대한 자체 점검(스프링 컨텍스트 없이 순수 로직만 검증).
 */
class ContractComparisonTest {
    @Test
    fun `대기 중인 경로를 제외하고 모두 일치하면 실패가 없다`() {
        val expected = mapOf("GET /api/v1/members/me" to setOf("200", "401"))
        val actual = mapOf("GET /api/v1/members/me" to setOf("200", "401"))

        val failures = ContractComparison.compare(expected, actual, pendingKeys = emptySet())

        assertThat(failures).isEmpty()
    }

    @Test
    fun `대기 중인 경로는 실제 API에 없어도 실패로 보지 않는다`() {
        val expected = mapOf("POST /api/v1/auth/signup" to setOf("201", "400", "409"))
        val actual = emptyMap<String, Set<String>>()

        val failures = ContractComparison.compare(expected, actual, pendingKeys = setOf("POST /api/v1/auth/signup"))

        assertThat(failures).isEmpty()
    }

    @Test
    fun `구현된 경로의 응답 상태 코드가 계약과 다르면 실패한다`() {
        val expected = mapOf("GET /api/v1/members/me" to setOf("200", "401"))
        val actual = mapOf("GET /api/v1/members/me" to setOf("200")) // 401 응답이 계약과 다름(누락)

        val failures = ContractComparison.compare(expected, actual, pendingKeys = emptySet())

        assertThat(failures).hasSize(1)
        assertThat(failures.single()).contains("GET /api/v1/members/me")
    }

    @Test
    fun `대기 중이 아닌 계약 경로가 API에 아예 없으면 실패한다`() {
        val expected = mapOf("GET /api/v1/members/me" to setOf("200", "401"))
        val actual = emptyMap<String, Set<String>>()

        val failures = ContractComparison.compare(expected, actual, pendingKeys = emptySet())

        assertThat(failures).hasSize(1)
        assertThat(failures.single()).contains("구현되었어야 할 경로가 API에 없습니다")
    }

    @Test
    fun `계약에 없는 api v1 경로를 API가 노출하면 실패한다`() {
        val expected = mapOf("GET /api/v1/members/me" to setOf("200", "401"))
        val actual =
            mapOf(
                "GET /api/v1/members/me" to setOf("200", "401"),
                "GET /api/v1/members/secret" to setOf("200"),
            )

        val failures = ContractComparison.compare(expected, actual, pendingKeys = emptySet())

        assertThat(failures).hasSize(1)
        assertThat(failures.single()).contains("계약에 없는 경로가 API에 노출되어 있습니다")
    }
}
