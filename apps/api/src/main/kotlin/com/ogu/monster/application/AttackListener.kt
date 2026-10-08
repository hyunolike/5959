package com.ogu.monster.application

import com.ogu.monster.domain.MonsterRepository
import com.ogu.post.Attack
import com.ogu.post.AttackAction
import com.ogu.post.CommentCreated
import com.ogu.post.CommentLiked
import com.ogu.post.PostApi
import com.ogu.post.PostLiked
import com.ogu.shared.lock.PostLock
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 공감과 댓글을 몬스터 HP에 반영한다(research R5, FR-006, FR-007a, FR-010). post 모듈의 트랜잭션 안에서 동기로 받아,
 * 공감 저장과 HP 감소가 함께 성공하거나 함께 실패한다. 글 잠금([PostLock])을 잡은 뒤 몬스터를 찾으므로 몬스터
 * 생성([MonsterFactory])과 겹쳐도 공격은 생성 전 목록(소급 반영)과 생성 후 감소 가운데 정확히 한 곳에 들어간다.
 */
@Component
class AttackListener(
    private val postApi: PostApi,
    private val postLock: PostLock,
    private val monsterRepository: MonsterRepository,
    private val attacks: MonsterAttacks,
    private val clock: Clock,
) {
    @EventListener
    fun on(event: PostLiked) {
        attack(event.postId, Attack(event.memberId, AttackAction.POST_LIKE, event.postId))
    }

    /** 댓글 감소는 회원당 글 하나에 한 번이라 대상은 글 ID다. */
    @EventListener
    fun on(event: CommentCreated) {
        attack(event.postId, Attack(event.memberId, AttackAction.COMMENT, event.postId))
    }

    @EventListener
    fun on(event: CommentLiked) {
        attack(event.postId, Attack(event.memberId, AttackAction.COMMENT_LIKE, event.commentId))
    }

    private fun attack(
        postId: Long,
        attack: Attack,
    ) {
        // 규칙 1: 작성자의 행동은 HP를 바꾸지 않는다(FR-007a). 지운 글에는 공격이 들어오지 않는다.
        val post = postApi.find(postId)
        if (post == null || post.authorId == attack.memberId) return
        // 잠금을 잡은 뒤에 몬스터를 찾아야 한다. 먼저 찾으면 커밋 전인 생성을 놓치고, 생성도 이 공격을 보지 못한다.
        postLock.lock(postId)
        // 앞의 확인과 잠금 사이에 삭제가 커밋됐을 수 있다. 댓글 공감은 posts 행을 잠그지 않아 삭제를 행에서 기다리지
        // 않으므로, 삭제가 쥔 글 잠금이 풀린 뒤 다시 확인한다.
        val stillLive = postApi.find(postId) != null
        // 규칙 2: 몬스터가 없으면 반영하지 않는다. 몬스터를 만들 때 소급 반영된다(FR-006a).
        val monsterId = (if (stillLive) monsterRepository.findIdByPostId(postId) else null) ?: return
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        attacks.apply(postId, monsterId, attack, retroactive = false, now = now)
    }
}
