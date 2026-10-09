package com.ogu.raid.application

import com.ogu.raid.RaidApi
import com.ogu.raid.domain.RaidContributionRepository
import org.springframework.stereotype.Service

/** [RaidApi]의 구현. 끝난 보스의 기록(Postgres)만 읽는다. */
@Service
class RaidFacade(
    private val contributions: RaidContributionRepository,
) : RaidApi {
    override fun participantIds(bossId: Long): List<Long> = contributions.participantIds(bossId)

    override fun defeatedCount(memberId: Long): Int = contributions.defeatedCount(memberId)
}
