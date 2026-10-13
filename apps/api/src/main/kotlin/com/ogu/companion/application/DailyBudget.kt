package com.ogu.companion.application

import com.ogu.companion.domain.CompanionReactionRepository
import com.ogu.companion.domain.CompanionRepository
import com.ogu.companion.domain.DailyUsageRepository
import com.ogu.companion.domain.Friend
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * 하루의 몫(009 research R8). 하루(한국 시간)에 AI에 댓글을 청하는 횟수를 `daily-limit`으로 묶는다. 댓글 수가 아니라 청한
 * 횟수로 세므로 실패한 시도도 몫을 쓴다. 공급자가 흔들리는 날의 재시도가 호출 한도를 먹지 않게 한다. 올라간 댓글 수는
 * 청한 횟수를 넘지 못한다(SC-007).
 */
@Component
class DailyBudget(
    private val usage: DailyUsageRepository,
    private val properties: CompanionProperties,
    private val clock: Clock,
) {
    /** 오늘의 몫 하나를 받는다. 남은 것이 없으면 false다. */
    fun reserve(): Boolean = usage.reserve(today(), properties.dailyLimit)

    /** 호출이 공급자에 나가지 않았을 때 받은 몫을 돌려준다. */
    fun release() {
        usage.release(today())
    }

    private fun today(): LocalDate = clock.instant().atZone(SEOUL).toLocalDate()

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

/**
 * 반응할 친구를 고른다(009 FR-011). 가장 오래 쉰 친구다: 반응한 적이 없는 친구가 먼저이고, 그다음은 가장 최근에 반응한
 * 글이 가장 오래된 친구다. 글이 차례로 오면 세 친구가 돌아가며 나온다(US4-AC2).
 */
@Component
class CompanionPicker(
    private val companions: CompanionRepository,
    private val reactions: CompanionReactionRepository,
) {
    fun all(): List<Friend> = companions.all()

    fun pick(): Friend {
        val lastDone = reactions.lastDoneByCompanion()
        return all().minWith(compareBy<Friend> { lastDone[it.id] ?: NEVER }.thenBy { it.id })
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
