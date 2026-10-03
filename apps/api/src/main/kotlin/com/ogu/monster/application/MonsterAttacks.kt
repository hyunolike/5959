package com.ogu.monster.application

import com.ogu.monster.MonsterDefeated
import com.ogu.monster.domain.MonsterHpLog
import com.ogu.monster.domain.MonsterHpLogRepository
import com.ogu.monster.domain.MonsterRepository
import com.ogu.post.Attack
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 공격 하나를 HP에 반영한다(data-model.md 반영 규칙 3, 4). 부르는 쪽이 글 잠금([PostLock])을 쥐고 있어야 한다.
 * 기록 삽입에 성공했을 때만 HP를 줄이고, 이번 공격으로 처치되면 [MonsterDefeated]를 한 번 발행한다(커밋 뒤에 구독자가
 * 받는다). 공감 반영([AttackListener])과 소급 반영([MonsterFactory])이 같이 쓴다.
 */
@Component
class MonsterAttacks(
    private val monsterRepository: MonsterRepository,
    private val hpLogRepository: MonsterHpLogRepository,
    private val events: ApplicationEventPublisher,
) {
    fun apply(
        postId: Long,
        monsterId: Long,
        attack: Attack,
        retroactive: Boolean,
        now: Instant,
    ) {
        val log = MonsterHpLog(monsterId, attack.memberId, attack.action, attack.targetId, retroactive, now)
        val logId = hpLogRepository.insertIfAbsent(log) ?: return
        val change = monsterRepository.decrementHp(monsterId, log.hpDelta, now)
        hpLogRepository.recordHp(logId, change)
        if (change.defeatedNow) events.publishEvent(MonsterDefeated(postId, monsterId))
    }
}
