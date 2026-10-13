package com.ogu.companion

import com.ogu.companion.application.CompanionProperties
import com.ogu.companion.application.ReactionCount
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Random

/** 한 글에 반응할 AI 친구의 수를 무게로 뽑는다(012 research R2). 씨앗을 고정해 비율을 잰다. */
class ReactionCountPickerTest {
    @Test
    fun `US1-AC2 만 번 뽑으면 1명과 2명과 3명이 60과 30과 10에서 2퍼센트포인트 안이다`() {
        val shares = sharesOf(listOf(60, 30, 10))

        assertThat(shares.getValue(1)).isCloseTo(60.0, WITHIN)
        assertThat(shares.getValue(2)).isCloseTo(30.0, WITHIN)
        assertThat(shares.getValue(3)).isCloseTo(10.0, WITHIN)
    }

    @Test
    fun `US7-AC2 무게를 바꾸면 비율이 따라 바뀐다`() {
        assertThat(sharesOf(listOf(0, 0, 100))).containsOnlyKeys(3)
        assertThat(sharesOf(listOf(100, 0, 0))).containsOnlyKeys(1)
        val even = sharesOf(listOf(1, 1, 1))
        assertThat(even.values).allSatisfy { assertThat(it).isCloseTo(33.3, WITHIN) }
    }

    @Test
    fun `US7-AC2 무게의 합이 100이 아니어도 비례로 뽑는다`() {
        val shares = sharesOf(listOf(6, 3, 1))

        assertThat(shares.getValue(1)).isCloseTo(60.0, WITHIN)
        assertThat(shares.getValue(3)).isCloseTo(10.0, WITHIN)
    }

    @Test
    fun `FR-001 무게가 모두 0이거나 음수가 섞였거나 비었으면 1명이다`() {
        val random = Random(SEED)

        for (weights in listOf(listOf(0, 0, 0), listOf(60, -1, 10), emptyList())) {
            repeat(100) { assertThat(ReactionCount.pick(weights, random)).isEqualTo(1) }
        }
    }

    @Test
    fun `FR-001 무게가 둘뿐이면 3명은 나오지 않고 넷째 자리부터는 보지 않는다`() {
        assertThat(sharesOf(listOf(50, 50))).containsOnlyKeys(1, 2)
        assertThat(sharesOf(listOf(0, 0, 0, 100))).containsOnlyKeys(1)
        assertThat(sharesOf(listOf(1, 1, 1, 100))).containsOnlyKeys(1, 2, 3)
    }

    @Test
    fun `FR-004 뒤따르는 간격은 가장 짧은 것과 가장 긴 것 사이에서 고른다`() {
        val random = Random(SEED)
        val followUp = CompanionProperties.FollowUp()

        val gaps = (1..1_000).map { followUp.gap(random) }

        assertThat(gaps).allSatisfy { assertThat(it).isBetween(Duration.ofMinutes(3), Duration.ofMinutes(10)) }
        assertThat(gaps.min()).isLessThan(Duration.ofMinutes(4))
        assertThat(gaps.max()).isGreaterThan(Duration.ofMinutes(9))
    }

    @Test
    fun `FR-004 가장 긴 간격이 더 짧게 적혀 있으면 가장 짧은 간격으로 맞춘다`() {
        val random = Random(SEED)
        val reversed = CompanionProperties.FollowUp(minGap = Duration.ofMinutes(5), maxGap = Duration.ofMinutes(1))
        val fixed = CompanionProperties.FollowUp(minGap = Duration.ofSeconds(2), maxGap = Duration.ofSeconds(2))

        assertThat(reversed.gap(random)).isEqualTo(Duration.ofMinutes(5))
        assertThat(fixed.gap(random)).isEqualTo(Duration.ofSeconds(2))
    }

    /** [weights]로 [DRAWS]번 뽑았을 때 수마다의 비율(%). 한 번도 나오지 않은 수는 없다. */
    private fun sharesOf(weights: List<Int>): Map<Int, Double> {
        val random = Random(SEED)
        return (1..DRAWS)
            .map { ReactionCount.pick(weights, random) }
            .groupingBy { it }
            .eachCount()
            .mapValues { it.value * 100.0 / DRAWS }
    }

    private companion object {
        const val SEED = 20261013L
        const val DRAWS = 10_000
        val WITHIN: Offset<Double> = Offset.offset(2.0)
    }
}
