package com.ogu.feed.presentation.dto

import com.ogu.emotion.EmotionType
import java.time.LocalDate

/** 계약의 `EmotionStats`(004 US4). 몬스터가 없으면 숫자는 모두 0이고 [topEmotion]은 null이다. */
data class EmotionStatsResponse(
    val totalMonsters: Int,
    val defeatedMonsters: Int,
    val defeatedTogether: Int,
    /** 내가 공격에 참여한 레이드 보스 가운데 처치된 수(006 US4-AC5). */
    val raidBossesDefeated: Int,
    val distribution: List<EmotionShareResponse>,
    val topEmotion: EmotionType?,
    val weekly: List<WeeklyEmotionCountResponse>,
)

/** 계약의 `EmotionShare`. [percent]는 정수이고 다섯 항목의 합은 100이다(몬스터가 없으면 모두 0). */
data class EmotionShareResponse(
    val emotion: EmotionType,
    val count: Int,
    val percent: Int,
)

/** 계약의 `WeeklyEmotionCount`. [weekStart]는 한국 시간 월요일 날짜다. */
data class WeeklyEmotionCountResponse(
    val weekStart: LocalDate,
    val counts: List<EmotionCountResponse>,
)

data class EmotionCountResponse(
    val emotion: EmotionType,
    val count: Int,
)
