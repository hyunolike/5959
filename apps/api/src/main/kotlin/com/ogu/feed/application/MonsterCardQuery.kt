package com.ogu.feed.application

import com.ogu.emotion.EmotionType
import com.ogu.feed.presentation.dto.EmotionCountResponse
import com.ogu.feed.presentation.dto.MonsterCardGrade
import com.ogu.feed.presentation.dto.MonsterCardPageResponse
import com.ogu.feed.presentation.dto.MonsterCardResponse
import com.ogu.feed.presentation.dto.MonsterCardRole
import com.ogu.feed.presentation.dto.MonsterCardSummaryResponse
import com.ogu.monster.MonsterApi
import com.ogu.monster.MonsterCardRow
import com.ogu.post.PostActivityApi
import com.ogu.post.PostSelectionApi
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Service

/**
 * 몬스터 도감(010 research R1~R6). 카드는 저장하지 않는다. 내 글의 번호, 처치된 몬스터와 내 공격의 기록, 보이는 글을
 * 파사드로 한 번씩 읽어 메모리에서 조립한다. `posts`와 `monsters`는 소유 모듈이 달라 조인하지 않는다. 쿼리는 카드 수와
 * 상관없이 3개다.
 *
 * 지웠거나 숨긴 글의 몬스터는 [PostSelectionApi.visibleIds]에서 빠져 카드가 되지 않는다. 내 글이 숨겨져도 같다(위기로
 * 가려진 글을 수집 카드로 보이지 않는다). 피드의 카드 조립기를 쓰지 않는 까닭은 그것이 본문을 싣기 때문이다.
 */
@Service
class MonsterCardQuery(
    private val postActivityApi: PostActivityApi,
    private val monsterApi: MonsterApi,
    private val postSelectionApi: PostSelectionApi,
) {
    fun page(
        memberId: Long,
        cursor: String?,
        size: Int,
        emotion: EmotionType?,
    ): MonsterCardPageResponse {
        if (size !in 1..MAX_SIZE) throw BusinessException(ErrorCode.INVALID_REQUEST, "size는 1~${MAX_SIZE}이어야 합니다.")
        val after = cursor?.let(MonsterCardCursor::decode)

        val ownPostIds = postActivityApi.liveRefsByAuthor(memberId).map { it.postId }.toSet()
        val rows = monsterApi.cardRows(memberId, ownPostIds)
        val visible = postSelectionApi.visibleIds(rows.map { it.postId })
        val cards = rows.filter { it.postId in visible }.sortedWith(NEWEST_FIRST)

        val remaining =
            cards
                .filter { emotion == null || it.emotion == emotion }
                .filter { after == null || it.isAfter(after) }
        val items = remaining.take(size)
        val nextCursor =
            items
                .lastOrNull()
                ?.takeIf { remaining.size > size }
                ?.let { MonsterCardCursor(it.defeatedAt, it.monsterId).encode() }
        return MonsterCardPageResponse(
            items = items.map { it.toResponse(mine = it.postId in ownPostIds) },
            nextCursor = nextCursor,
            summary = summaryOf(cards),
        )
    }

    private fun summaryOf(cards: List<MonsterCardRow>): MonsterCardSummaryResponse =
        MonsterCardSummaryResponse(
            total = cards.size,
            byEmotion =
                EmotionType.entries.map { emotion ->
                    EmotionCountResponse(emotion, cards.count { it.emotion == emotion })
                },
        )

    /** 정렬 순서에서 [cursor]의 카드보다 뒤에 오는가. */
    private fun MonsterCardRow.isAfter(cursor: MonsterCardCursor): Boolean =
        defeatedAt < cursor.defeatedAt || (defeatedAt == cursor.defeatedAt && monsterId < cursor.cardNo)

    private fun MonsterCardRow.toResponse(mine: Boolean): MonsterCardResponse =
        MonsterCardResponse(
            cardNo = monsterId,
            emotion = emotion,
            grade = MonsterCardGrade.of(maxHp),
            role = if (mine) MonsterCardRole.MINE else MonsterCardRole.TOGETHER,
            defeatedAt = defeatedAt,
            // 글쓴이의 반응은 HP를 줄이지 않는다. 그래서 내 몬스터의 카드는 함께한 회원 수를 보인다
            myDamage = if (mine) 0 else damageBy,
            helperCount = if (mine) damagerCount else null,
            postId = postId,
        )

    companion object {
        const val MAX_SIZE = 50
        private val NEWEST_FIRST =
            compareByDescending<MonsterCardRow> { it.defeatedAt }.thenByDescending { it.monsterId }
    }
}
