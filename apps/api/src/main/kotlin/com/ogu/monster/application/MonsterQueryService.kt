package com.ogu.monster.application

import com.ogu.monster.MonsterApi
import com.ogu.monster.MonsterView
import com.ogu.monster.domain.MonsterRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class MonsterQueryService(
    private val monsterRepository: MonsterRepository,
) : MonsterApi {
    override fun findByPostIds(postIds: Collection<Long>): Map<Long, MonsterView> {
        if (postIds.isEmpty()) return emptyMap()
        return monsterRepository.findAllByPostIdIn(postIds.toSet()).associate { it.postId to it.toView() }
    }
}
