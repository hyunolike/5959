package com.ogu.feed.application

import com.ogu.emotion.EmotionType
import com.ogu.emotion.EmotionType.ANXIETY
import com.ogu.emotion.EmotionType.IRRITATION
import com.ogu.emotion.EmotionType.LETHARGY
import com.ogu.emotion.EmotionType.LONELINESS
import com.ogu.emotion.EmotionType.SELF_DEPRECATION
import com.ogu.feed.presentation.dto.EmotionStatsResponse
import com.ogu.monster.MonsterApi
import com.ogu.monster.MonsterStatRow
import com.ogu.monster.MonsterStatus
import com.ogu.post.PostActivityApi
import com.ogu.post.PostRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * T053: 감정 통계 계산(004 US4, research R12). 파사드는 가짜로 두고 시계를 고정해 반올림, 동률, 주 경계를 본다.
 * 지금은 2026-10-08(목) 12:00 한국 시간이고, 이번 주는 10-05(월)에 시작한다.
 */
class EmotionStatsQueryTest {
    private val postApi = mock(PostActivityApi::class.java)
    private val monsterApi = mock(MonsterApi::class.java)
    private val query = EmotionStatsQuery(postApi, monsterApi, Clock.fixed(NOW, ZoneOffset.UTC))
    private var nextPostId = 1L
    private val refs = mutableListOf<PostRef>()
    private val rows = mutableListOf<MonsterStatRow>()

    @Test
    fun `US4-AC1 비율은 정수 퍼센트이고 합이 100이며 반올림 오차는 가장 큰 항목에서 맞춘다`() {
        // 1:1:1이면 33.3%씩이라 합이 99다. 가장 최근 몬스터의 감정(외로움)에 1을 더한다
        monster(ANXIETY, spawnedAt = NOW.minusSeconds(30))
        monster(LETHARGY, spawnedAt = NOW.minusSeconds(20))
        monster(LONELINESS, spawnedAt = NOW.minusSeconds(10))

        val stats = stats()

        assertThat(percents(stats)).containsExactly(33, 33, 34, 0, 0)
        assertThat(counts(stats)).containsExactly(1, 1, 1, 0, 0)
        assertThat(stats.distribution.map { it.emotion }).containsExactlyElementsOf(EmotionType.entries)
        assertThat(stats.topEmotion).isEqualTo(LONELINESS)
    }

    @Test
    fun `US4-AC1 반올림한 합이 100을 넘으면 가장 큰 항목에서 뺀다`() {
        // 3:3:1:1이면 37.5%, 37.5%, 12.5%, 12.5%라 반올림한 합이 38 + 38 + 13 + 13 = 102다
        repeat(3) { monster(ANXIETY, spawnedAt = NOW.minusSeconds(100)) }
        repeat(3) { monster(LETHARGY, spawnedAt = NOW.minusSeconds(50)) }
        monster(LONELINESS)
        monster(IRRITATION)

        val stats = stats()

        // 불안과 무기력이 3으로 같고, 무기력의 몬스터가 더 최근이라 무기력에서 2를 뺀다
        assertThat(stats.topEmotion).isEqualTo(LETHARGY)
        assertThat(percents(stats)).containsExactly(38, 36, 13, 0, 13)
        assertThat(percents(stats).sum()).isEqualTo(100)
    }

    @Test
    fun `US4-AC1 전체 수와 처치된 수를 센다`() {
        monster(ANXIETY, status = MonsterStatus.DEFEATED)
        monster(ANXIETY)
        monster(IRRITATION, status = MonsterStatus.DEFEATED)
        // 분석 중인 글은 몬스터가 없어 세지 않는다
        post(NOW.minusSeconds(5))

        val stats = stats()

        assertThat(stats.totalMonsters).isEqualTo(3)
        assertThat(stats.defeatedMonsters).isEqualTo(2)
        assertThat(percents(stats)).containsExactly(67, 0, 0, 0, 33)
    }

    @Test
    fun `US4-AC2 가장 많은 감정이 하나면 그 감정이다`() {
        repeat(2) { monster(SELF_DEPRECATION, spawnedAt = NOW.minusSeconds(500)) }
        monster(ANXIETY, spawnedAt = NOW)

        assertThat(stats().topEmotion).isEqualTo(SELF_DEPRECATION)
    }

    @Test
    fun `US4-AC2 가장 많은 감정이 같으면 그 가운데 가장 최근 몬스터의 감정`() {
        monster(IRRITATION, spawnedAt = NOW.minusSeconds(300))
        monster(ANXIETY, spawnedAt = NOW.minusSeconds(200))
        monster(IRRITATION, spawnedAt = NOW.minusSeconds(100))
        monster(ANXIETY, spawnedAt = NOW.minusSeconds(50))
        // 더 최근이지만 수가 적은 감정은 고르지 않는다
        monster(LONELINESS, spawnedAt = NOW)

        assertThat(stats().topEmotion).isEqualTo(ANXIETY)
    }

    @Test
    fun `US4-AC3 8주는 한국 시간 월요일 0시 시작이고 오래된 주부터이며 빈 주는 0`() {
        // 이번 주 월요일 00:00:00 KST는 UTC로 일요일 15:00다
        val thisMonday = Instant.parse("2026-10-04T15:00:00Z")
        monster(ANXIETY, postedAt = thisMonday)
        monster(LETHARGY, postedAt = thisMonday.minusSeconds(1))
        monster(LETHARGY, postedAt = thisMonday.minus(Duration.ofDays(7)))
        // 7주 전 월요일 0시는 들어가고, 그 1초 전(8주 전 일요일)은 빠진다
        val oldestMonday = thisMonday.minus(Duration.ofDays(49))
        monster(IRRITATION, postedAt = oldestMonday)
        monster(IRRITATION, postedAt = oldestMonday.minusSeconds(1))
        // 글은 3주 전에 썼지만 분석이 늦어 몬스터는 이번 주에 생겼다. 쓴 주에 든다
        monster(LONELINESS, postedAt = thisMonday.minus(Duration.ofDays(20)), spawnedAt = NOW)

        val weekly = stats().weekly

        assertThat(weekly.map { it.weekStart }).containsExactly(
            LocalDate.parse("2026-08-17"),
            LocalDate.parse("2026-08-24"),
            LocalDate.parse("2026-08-31"),
            LocalDate.parse("2026-09-07"),
            LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-21"),
            LocalDate.parse("2026-09-28"),
            LocalDate.parse("2026-10-05"),
        )
        assertThat(weekly).allSatisfy { week ->
            assertThat(week.counts.map { it.emotion }).containsExactlyElementsOf(EmotionType.entries)
        }
        val byWeek = weekly.map { week -> week.counts.map { it.count } }
        assertThat(byWeek[0]).containsExactly(0, 0, 0, 0, 1)
        assertThat(byWeek[1]).containsExactly(0, 0, 0, 0, 0)
        assertThat(byWeek[4]).containsExactly(0, 0, 1, 0, 0)
        assertThat(byWeek[6]).containsExactly(0, 2, 0, 0, 0)
        assertThat(byWeek[7]).containsExactly(1, 0, 0, 0, 0)
        assertThat(byWeek.flatten().sum()).isEqualTo(5)
    }

    @Test
    fun `US4-AC4 몬스터가 없으면 모두 0이고 가장 많은 감정은 null`() {
        val stats = stats()

        assertThat(stats.totalMonsters).isZero()
        assertThat(stats.defeatedMonsters).isZero()
        assertThat(stats.defeatedTogether).isZero()
        assertThat(stats.topEmotion).isNull()
        assertThat(percents(stats)).containsExactly(0, 0, 0, 0, 0)
        assertThat(counts(stats)).containsExactly(0, 0, 0, 0, 0)
        assertThat(stats.weekly).hasSize(8)
        assertThat(stats.weekly.flatMap { week -> week.counts.map { it.count } }).containsOnly(0)
    }

    @Test
    fun `US4-AC5 함께 물리친 몬스터는 지운 글을 뺀 수다`() {
        `when`(monsterApi.defeatedPostIdsDamagedBy(ME)).thenReturn(setOf(101L, 102L, 103L))
        `when`(postApi.liveIds(setOf(101L, 102L, 103L))).thenReturn(setOf(101L, 103L))

        assertThat(stats().defeatedTogether).isEqualTo(2)
    }

    private fun post(postedAt: Instant): Long {
        val postId = nextPostId++
        refs += PostRef(postId, postedAt)
        return postId
    }

    private fun monster(
        emotion: EmotionType,
        status: MonsterStatus = MonsterStatus.ALIVE,
        postedAt: Instant = NOW.minusSeconds(60),
        spawnedAt: Instant = postedAt,
    ) {
        rows += MonsterStatRow(post(postedAt), emotion, status, spawnedAt)
    }

    private fun stats(): EmotionStatsResponse {
        `when`(postApi.liveRefsByAuthor(ME)).thenReturn(refs)
        `when`(monsterApi.statRows(refs.map { it.postId }.toSet())).thenReturn(rows)
        return query.get(ME)
    }

    private fun percents(stats: EmotionStatsResponse): List<Int> = stats.distribution.map { it.percent }

    private fun counts(stats: EmotionStatsResponse): List<Int> = stats.distribution.map { it.count }

    private companion object {
        const val ME = 7L
        val NOW: Instant = Instant.parse("2026-10-08T03:00:00Z")
    }
}
