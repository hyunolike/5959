package com.ogu.monster.domain

import org.springframework.data.jpa.repository.JpaRepository

interface MonsterRepository :
    JpaRepository<Monster, Long>,
    MonsterHpUpdates {
    fun existsByPostId(postId: Long): Boolean

    fun findAllByPostIdIn(postIds: Collection<Long>): List<Monster>
}
