package com.ogu.monster.domain

import com.ogu.emotion.EmotionType
import com.ogu.emotion.Intensity
import com.ogu.monster.MonsterStatus
import com.ogu.monster.MonsterView
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 글 하나의 몬스터(data-model.md `monsters`, FR-005). 감정 강도가 최대 HP를 정하고 HP 가득 찬 상태로 생긴다(US1-AC4).
 * HP 감소는 US3(T043)의 원자적 UPDATE로만 한다.
 */
@Entity
@Table(name = "monsters")
class Monster private constructor(
    postId: Long,
    emotion: EmotionType,
    maxHp: Int,
    createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0L
        protected set

    @Column(name = "post_id", nullable = false, updatable = false, unique = true)
    var postId: Long = postId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "emotion", nullable = false, updatable = false, length = 20)
    var emotion: EmotionType = emotion
        protected set

    @Column(name = "max_hp", nullable = false, updatable = false)
    var maxHp: Int = maxHp
        protected set

    @Column(name = "hp", nullable = false)
    var hp: Int = maxHp
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    var status: MonsterStatus = MonsterStatus.ALIVE
        protected set

    @Column(name = "defeated_at")
    var defeatedAt: Instant? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = createdAt
        protected set

    fun toView(): MonsterView = MonsterView(emotion, hp, maxHp, status)

    companion object {
        fun spawn(
            postId: Long,
            emotion: EmotionType,
            intensity: Intensity,
            now: Instant,
        ): Monster = Monster(postId, emotion, intensity.maxHp, now)
    }
}
