package com.ogu.post

/**
 * 몬스터가 생기기 전에 쌓인 공격 하나. [targetId]는 [AttackAction.POST_LIKE]와 [AttackAction.COMMENT]면 글 ID,
 * [AttackAction.COMMENT_LIKE]면 댓글 ID다.
 */
data class Attack(
    val memberId: Long,
    val action: AttackAction,
    val targetId: Long,
)
