package com.ogu.raid.application

import com.ogu.emotion.AnalysisStatus
import com.ogu.emotion.EmotionApi
import com.ogu.emotion.EmotionType
import com.ogu.emotion.EmotionView
import com.ogu.emotion.Intensity
import com.ogu.post.PostApi
import com.ogu.raid.domain.RaidBoss
import com.ogu.raid.domain.RaidBossStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant

/** T024: 새 보스의 감정과 HP(006 US5-AC2, AC4, research R9). 파사드는 가짜로 둔다. */
class RaidBossPlannerTest {
    private val postApi = mock(PostApi::class.java)
    private val emotionApi = mock(EmotionApi::class.java)
    private val planner = RaidBossPlanner(postApi, emotionApi, RaidProperties())

    @Test
    fun `US5-AC2 최근 7일의 글에서 가장 많은 감정이 보스의 감정이 된다`() {
        analyzed(
            EmotionType.ANXIETY,
            EmotionType.IRRITATION,
            EmotionType.IRRITATION,
            EmotionType.LONELINESS,
            EmotionType.IRRITATION,
            null,
        )

        assertThat(planner.emotion(NOW)).isEqualTo(EmotionType.IRRITATION)
        // 7일 전부터 센다
        verify(postApi).visibleIdsSince(NOW.minus(Duration.ofDays(7)), MAX_POSTS)
    }

    @Test
    fun `US5-AC2 수가 같으면 정해진 순서에서 앞선 감정이고 분석이 끝난 글이 없으면 무기력이다`() {
        analyzed(EmotionType.IRRITATION, EmotionType.LONELINESS, EmotionType.IRRITATION, EmotionType.LONELINESS)
        assertThat(planner.emotion(NOW)).isEqualTo(EmotionType.LONELINESS)

        analyzed(EmotionType.SELF_DEPRECATION, EmotionType.ANXIETY)
        assertThat(planner.emotion(NOW)).isEqualTo(EmotionType.ANXIETY)

        analyzed(null, null)
        assertThat(planner.emotion(NOW)).isEqualTo(EmotionType.LETHARGY)

        analyzed()
        assertThat(planner.emotion(NOW)).isEqualTo(EmotionType.LETHARGY)
    }

    @ParameterizedTest(name = "[{index}] 직전 {0}, 참여자 {1}명 → HP {2}")
    @CsvSource(
        "DEFEATED, 0, 300",
        "DEFEATED, 2, 300",
        "DEFEATED, 3, 300",
        "DEFEATED, 7, 700",
        "DEFEATED, 50, 5000",
        "DEFEATED, 51, 5000",
        "DEFEATED, 100000000, 5000",
        "RETREATED, 40, 300",
    )
    fun `US5-AC4 HP는 직전 참여자 수에 100을 곱해 300과 5000 사이로 자른다`(
        status: RaidBossStatus,
        participants: Int,
        expected: Int,
    ) {
        assertThat(planner.maxHp(boss(status, participants))).isEqualTo(expected)
    }

    @Test
    fun `US5-AC1 직전 보스가 없으면 HP는 300이다`() {
        assertThat(planner.maxHp(previous = null)).isEqualTo(300)
    }

    /** 글마다 분석 결과를 하나씩 준다. null은 아직 분석이 끝나지 않은 글이다. */
    private fun analyzed(vararg emotions: EmotionType?) {
        val views =
            emotions
                .mapIndexed { index, emotion ->
                    val view =
                        if (emotion == null) {
                            EmotionView(AnalysisStatus.PENDING, null, null)
                        } else {
                            EmotionView(AnalysisStatus.ANALYZED, emotion, Intensity.LOW)
                        }
                    (index + 1).toLong() to view
                }.toMap()
        `when`(postApi.visibleIdsSince(NOW.minus(Duration.ofDays(7)), MAX_POSTS)).thenReturn(views.keys.toList())
        `when`(emotionApi.findByPostIds(anyCollection())).thenReturn(views)
    }

    private fun boss(
        status: RaidBossStatus,
        participants: Int,
    ) = RaidBoss(
        id = 1,
        emotion = EmotionType.ANXIETY,
        maxHp = 300,
        hp = if (status == RaidBossStatus.DEFEATED) 0 else 10,
        status = status,
        participantCount = participants,
        spawnedAt = NOW.minus(Duration.ofDays(1)),
        endedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-09T03:00:00Z")
        const val MAX_POSTS = 10_000
    }
}
