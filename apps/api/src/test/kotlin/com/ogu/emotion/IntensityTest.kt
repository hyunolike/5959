package com.ogu.emotion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class IntensityTest {
    @ParameterizedTest(name = "{0}이면 최대 HP는 {1}이다")
    @CsvSource("LOW, 10", "MEDIUM, 20", "HIGH, 30")
    fun `강도에 따라 몬스터 최대 HP가 정해진다`(
        intensity: Intensity,
        maxHp: Int,
    ) {
        assertThat(intensity.maxHp).isEqualTo(maxHp)
    }
}
