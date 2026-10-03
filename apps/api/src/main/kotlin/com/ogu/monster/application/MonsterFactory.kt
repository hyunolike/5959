package com.ogu.monster.application

import com.ogu.emotion.EmotionAnalyzed
import com.ogu.monster.domain.Monster
import com.ogu.monster.domain.MonsterRepository
import com.ogu.post.PostApi
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 감정 분석이 끝나면(커밋 후, 비동기) 그 글의 몬스터를 HP 가득 찬 상태로 만든다(research R4, US1-AC4, US1-AC6).
 * 글 단위 잠금([PostLock]) 안에서 만들고 그때까지 쌓인 공격을 소급 반영해, 같은 글의 공격 반영([AttackListener])과
 * 겹쳐도 공격이 빠지거나 두 번 들어가지 않는다(research R4).
 * 같은 이벤트가 다시 전달돼도(Event Publication Registry 재발행) 몬스터는 하나다. 그사이 지운 글에는 만들지 않는다.
 */
@Component
class MonsterFactory(
    private val monsterRepository: MonsterRepository,
    private val postLock: PostLock,
    private val postApi: PostApi,
    private val attacks: MonsterAttacks,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun on(event: EmotionAnalyzed) {
        postLock.lock(event.postId)
        if (postApi.find(event.postId) == null) {
            // 분석 전이나 분석 중에 지운 글이다. 어디에도 보이지 않으므로 몬스터를 만들지 않는다.
            log.info("지운 글이라 몬스터를 만들지 않습니다: postId={}", event.postId)
            return
        }
        if (monsterRepository.existsByPostId(event.postId)) {
            log.info("이미 몬스터가 있어 다시 만들지 않습니다: postId={}", event.postId)
            return
        }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val monster = monsterRepository.save(Monster.spawn(event.postId, event.emotion, event.intensity, now))
        applyRetroactiveAttacks(monster, now)
    }

    /**
     * 몬스터가 생기기 전에 쌓인 공격(`PostApi.attacksSoFar`)을 같은 잠금과 트랜잭션 안에서 `retroactive=true`로 HP에
     * 반영한다(US3-AC9, FR-006a). 그 사이 취소된 공감과 지운 댓글, 작성자의 행동은 목록에 없다. HP가 0이 되면 몬스터는
     * 처치된 상태로 커밋되고 [com.ogu.monster.MonsterDefeated]가 한 번 발행된다.
     */
    private fun applyRetroactiveAttacks(
        monster: Monster,
        now: Instant,
    ) {
        val attacksSoFar = postApi.attacksSoFar(monster.postId)
        attacksSoFar.forEach { attacks.apply(monster.postId, monster.id, it, retroactive = true, now = now) }
        if (attacksSoFar.isNotEmpty()) {
            log.info("몬스터를 만들며 공격 {}건을 소급 반영했습니다: postId={}", attacksSoFar.size, monster.postId)
        }
    }
}
