package com.ogu.monster.domain

import com.ogu.monster.MonsterStatus
import com.ogu.post.AttackAction
import java.time.Instant

/**
 * HP 기록 한 줄(data-model.md `monster_hp_log`, FR-009). 유일 키 `(monster_id, member_id, action, target_id)`가
 * "공감은 한 번", "댓글 감소는 회원당 글 하나에 한 번", "취소 뒤 다시 공감해도 다시 줄지 않음"을 보장한다(research R5).
 */
data class MonsterHpLog(
    val monsterId: Long,
    val memberId: Long,
    val action: AttackAction,
    val targetId: Long,
    val retroactive: Boolean,
    val createdAt: Instant,
) {
    val hpDelta: Int
        get() = damageOf(action)

    companion object {
        /** 행동별 감소량: 글 공감 1, 댓글 3, 댓글 공감 1(FR-006). */
        fun damageOf(action: AttackAction): Int =
            when (action) {
                AttackAction.POST_LIKE -> 1
                AttackAction.COMMENT -> 3
                AttackAction.COMMENT_LIKE -> 1
            }
    }
}

/** HP를 줄인 결과. 처치된 몬스터를 다시 공격하면 [hpBefore]와 [hpAfter]가 모두 0이다. */
data class HpChange(
    val hpBefore: Int,
    val hpAfter: Int,
    val status: MonsterStatus,
) {
    /** 이번 공격으로 처치됐다. 처치된 뒤의 공격은 해당하지 않는다. */
    val defeatedNow: Boolean
        get() = hpBefore > 0 && hpAfter == 0
}
