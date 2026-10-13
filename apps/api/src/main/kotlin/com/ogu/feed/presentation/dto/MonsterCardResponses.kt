package com.ogu.feed.presentation.dto

import com.ogu.emotion.EmotionType
import java.time.Instant

/** 계약의 `MonsterCardGrade`(010 research R5). 무작위가 아니라 몬스터의 세기로 정한다. */
enum class MonsterCardGrade {
    COMMON,
    RARE,
    LEGENDARY,
    ;

    companion object {
        private const val COMMON_MAX_HP = 10
        private const val RARE_MAX_HP = 20

        fun of(maxHp: Int): MonsterCardGrade =
            when {
                maxHp <= COMMON_MAX_HP -> COMMON
                maxHp <= RARE_MAX_HP -> RARE
                else -> LEGENDARY
            }
    }
}

/** 계약의 `MonsterCardRole`. 내 글의 몬스터인지, 다른 회원의 몬스터를 함께 물리친 것인지. */
enum class MonsterCardRole {
    MINE,
    TOGETHER,
}

/**
 * 계약의 `MonsterCard`(010 research R4). 글의 본문, 미리보기, 글쓴이, 함께 물리친 회원이 누구인지가 들어갈 자리가 없다
 * (FR-007). [helperCount]는 내 몬스터일 때만 있다.
 */
data class MonsterCardResponse(
    val cardNo: Long,
    val emotion: EmotionType,
    val grade: MonsterCardGrade,
    val role: MonsterCardRole,
    val defeatedAt: Instant,
    val myDamage: Int,
    val helperCount: Int?,
    val postId: Long,
)

/** 계약의 `MonsterCardSummary`. 감정으로 거르기 전의 전체다. [byEmotion]에는 다섯 감정이 모두 있다. */
data class MonsterCardSummaryResponse(
    val total: Int,
    val byEmotion: List<EmotionCountResponse>,
)

/** 계약의 `MonsterCardPage`. 다음 쪽이 없으면 [nextCursor]는 null이다. */
data class MonsterCardPageResponse(
    val items: List<MonsterCardResponse>,
    val nextCursor: String?,
    val summary: MonsterCardSummaryResponse,
)
