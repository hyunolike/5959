package com.ogu.feed.application

import com.ogu.emotion.EmotionType
import com.ogu.feed.presentation.dto.EmotionCountResponse
import com.ogu.feed.presentation.dto.EmotionShareResponse
import com.ogu.feed.presentation.dto.EmotionStatsResponse
import com.ogu.feed.presentation.dto.WeeklyEmotionCountResponse
import com.ogu.monster.MonsterApi
import com.ogu.monster.MonsterStatRow
import com.ogu.monster.MonsterStatus
import com.ogu.post.PostActivityApi
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * 마이페이지 감정 통계(004 US4, research R12). 내 살아 있는 글과 그 몬스터를 파사드로 읽어 메모리에서 센다. `posts`와
 * `monsters`는 소유 모듈이 달라 SQL로 조인하지 않는다. 쿼리는 글 수와 상관없이 4개 이하다.
 */
@Service
class EmotionStatsQuery(
    private val postActivityApi: PostActivityApi,
    private val monsterApi: MonsterApi,
    private val clock: Clock,
) {
    fun get(memberId: Long): EmotionStatsResponse {
        val postedAt = postActivityApi.liveRefsByAuthor(memberId).associate { it.postId to it.createdAt }
        val monsters = monsterApi.statRows(postedAt.keys)
        val counts = EmotionType.entries.associateWith { emotion -> monsters.count { it.emotion == emotion } }
        val topEmotion = topEmotion(monsters, counts)
        val defeatedTogether = postActivityApi.liveIds(monsterApi.defeatedPostIdsDamagedBy(memberId)).size
        return EmotionStatsResponse(
            totalMonsters = monsters.size,
            defeatedMonsters = monsters.count { it.status == MonsterStatus.DEFEATED },
            defeatedTogether = defeatedTogether,
            distribution = distribution(counts, topEmotion),
            topEmotion = topEmotion,
            weekly = weekly(monsters, postedAt),
        )
    }

    /**
     * 수가 가장 큰 감정(US4-AC2). 같으면 그 감정들 가운데 가장 최근에 생긴 몬스터의 감정이다. 생긴 시각까지 같으면
     * 나중 글(글 ID가 큰 쪽)의 감정으로 정해 결과가 흔들리지 않게 한다. 몬스터가 없으면 null이다.
     */
    private fun topEmotion(
        monsters: List<MonsterStatRow>,
        counts: Map<EmotionType, Int>,
    ): EmotionType? {
        val max = counts.values.max()
        if (max == 0) return null
        return monsters
            .filter { counts.getValue(it.emotion) == max }
            .maxWith(compareBy<MonsterStatRow> { it.createdAt }.thenBy { it.postId })
            .emotion
    }

    /**
     * 정수 퍼센트(US4-AC1). 반올림한 합이 100이 아니면 차이를 가장 많은 감정([topEmotion])에서 맞춘다.
     * 몬스터가 없으면 모두 0이다(US4-AC4).
     */
    private fun distribution(
        counts: Map<EmotionType, Int>,
        topEmotion: EmotionType?,
    ): List<EmotionShareResponse> {
        val total = counts.values.sum()
        if (total == 0 || topEmotion == null) {
            return EmotionType.entries.map { EmotionShareResponse(it, 0, 0) }
        }
        val rounded = counts.mapValues { (_, count) -> (count * PERCENT_TOTAL.toDouble() / total).roundToInt() }
        val remainder = PERCENT_TOTAL - rounded.values.sum()
        return EmotionType.entries.map { emotion ->
            val percent = rounded.getValue(emotion) + if (emotion == topEmotion) remainder else 0
            EmotionShareResponse(emotion, counts.getValue(emotion), percent)
        }
    }

    /**
     * 최근 8주(이번 주 포함, 오래된 주부터)의 감정별 몬스터 수(US4-AC3). 주는 한국 시간 월요일 0시에 시작하고,
     * 몬스터가 생긴 시각이 아니라 글을 쓴 시각으로 묶는다(분석이 늦어진 몬스터도 쓴 주에 든다). 빈 주는 0으로 채운다.
     */
    private fun weekly(
        monsters: List<MonsterStatRow>,
        postedAt: Map<Long, Instant>,
    ): List<WeeklyEmotionCountResponse> {
        val thisWeek = weekStart(clock.instant())
        val byWeek =
            monsters.groupBy { monster -> weekStart(postedAt.getValue(monster.postId)) }
        return (WEEKS - 1 downTo 0).map { weeksAgo ->
            val weekStart = thisWeek.minusWeeks(weeksAgo.toLong())
            val inWeek = byWeek[weekStart].orEmpty()
            WeeklyEmotionCountResponse(
                weekStart = weekStart,
                counts =
                    EmotionType.entries.map { emotion ->
                        EmotionCountResponse(emotion, inWeek.count { it.emotion == emotion })
                    },
            )
        }
    }

    private fun weekStart(instant: Instant): LocalDate =
        instant.atZone(SEOUL).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        const val WEEKS = 8
        const val PERCENT_TOTAL = 100
    }
}
