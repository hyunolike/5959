package com.ogu.monster.application

import com.ogu.monster.MonsterApi
import com.ogu.monster.MonsterStatRow
import com.ogu.monster.MonsterView
import com.ogu.monster.domain.MonsterHpLogRepository
import com.ogu.monster.domain.MonsterRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class MonsterQueryService(
    private val monsterRepository: MonsterRepository,
    private val hpLogRepository: MonsterHpLogRepository,
) : MonsterApi {
    override fun findByPostIds(postIds: Collection<Long>): Map<Long, MonsterView> {
        if (postIds.isEmpty()) return emptyMap()
        return monsterRepository.findAllByPostIdIn(postIds.toSet()).associate { it.postId to it.toView() }
    }

    override fun hasCountedComment(
        postId: Long,
        memberId: Long,
    ): Boolean = hpLogRepository.existsComment(postId, memberId)

    override fun damagerIds(monsterId: Long): Set<Long> = hpLogRepository.damagerIds(monsterId)

    override fun statRows(postIds: Collection<Long>): List<MonsterStatRow> {
        if (postIds.isEmpty()) return emptyList()
        return monsterRepository
            .findAllByPostIdIn(postIds.toSet())
            .map { MonsterStatRow(it.postId, it.emotion, it.status, it.createdAt) }
    }

    override fun defeatedPostIdsDamagedBy(memberId: Long): Set<Long> = hpLogRepository.defeatedPostIds(memberId)
}
