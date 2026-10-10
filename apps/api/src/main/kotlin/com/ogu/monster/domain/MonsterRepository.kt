package com.ogu.monster.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface MonsterRepository :
    JpaRepository<Monster, Long>,
    MonsterHpUpdates {
    fun existsByPostId(postId: Long): Boolean

    fun findAllByPostIdIn(postIds: Collection<Long>): List<Monster>

    fun countByPostIdInAndDefeatedAtGreaterThanEqualAndDefeatedAtLessThan(
        postIds: Collection<Long>,
        from: Instant,
        until: Instant,
    ): Int
}
