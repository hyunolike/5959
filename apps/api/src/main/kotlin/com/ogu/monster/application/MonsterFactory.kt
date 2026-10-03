package com.ogu.monster.application

import com.ogu.emotion.EmotionAnalyzed
import com.ogu.monster.domain.Monster
import com.ogu.monster.domain.MonsterRepository
import com.ogu.post.PostApi
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 감정 분석이 끝나면(커밋 후, 비동기) 그 글의 몬스터를 HP 가득 찬 상태로 만든다(research R4, US1-AC4, US1-AC6).
 * 글 단위 잠금([PostLock]) 안에서 만들어, 같은 글의 공격 반영(US3 AttackListener)과 겹치지 않는다.
 * 같은 이벤트가 다시 전달돼도(Event Publication Registry 재발행) 몬스터는 하나다. 그사이 지운 글에는 만들지 않는다.
 */
@Component
class MonsterFactory(
    private val monsterRepository: MonsterRepository,
    private val postLock: PostLock,
    private val postApi: PostApi,
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
        applyRetroactiveAttacks(monster)
    }

    /**
     * 확장 지점(T043, US3-AC9, FR-006a): 몬스터가 생기기 전에 쌓인 공격(`PostApi.attacksSoFar`)을 같은 잠금과 트랜잭션
     * 안에서 `retroactive=true`로 HP에 반영한다. US1 범위에서는 공격이 없으므로 아무것도 하지 않는다.
     */
    @Suppress("UnusedParameter")
    private fun applyRetroactiveAttacks(monster: Monster) = Unit
}
