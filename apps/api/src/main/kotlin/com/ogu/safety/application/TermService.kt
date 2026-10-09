package com.ogu.safety.application

import com.ogu.safety.domain.ModerationActionRepository
import com.ogu.safety.domain.ModerationActionType
import com.ogu.safety.domain.NewModerationAction
import com.ogu.safety.domain.SafetyTermRepository
import com.ogu.safety.domain.TermKind
import com.ogu.safety.domain.TermRow
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 운영자가 낱말 목록을 고친다(005 FR-002, FR-014, research R4). 낱말은 판정할 때와 같은 방식으로 다듬어 저장한다.
 * 고친 인스턴스는 커밋 뒤 바로 목록을 다시 읽고, 다른 인스턴스는 `ogu.safety.term-refresh-interval` 안에 따라온다.
 * 이미 쓰인 글은 다시 판정하지 않는다. 욕설은 읽을 때 가리므로 바로 반영된다.
 */
@Service
class TermService(
    private val terms: SafetyTermRepository,
    private val actions: ModerationActionRepository,
    private val termCache: TermCache,
    private val transaction: TransactionTemplate,
    private val clock: Clock,
) {
    fun list(kind: TermKind?): List<TermRow> = terms.list(kind)

    /** 다듬은 낱말이 2~20글자가 아니면 400이다. 이미 있으면 기록 없이 그 낱말을 돌려준다. */
    fun add(
        operatorId: Long,
        kind: TermKind,
        raw: String,
    ): TermRow {
        val term = TextNormalizer.termOf(raw)
        if (term.codePointCount(0, term.length) !in MIN_LENGTH..MAX_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_REQUEST, "낱말은 $MIN_LENGTH~${MAX_LENGTH}글자여야 합니다.")
        }
        val saved =
            transaction.execute {
                val now = now()
                val added = terms.insertIfAbsent(kind, term, now)
                if (added != null) record(operatorId, ModerationActionType.ADD_TERM, added.termId, now)
                added ?: terms.find(kind, term)
            } ?: throw BusinessException(ErrorCode.NOT_FOUND)
        termCache.refreshNow()
        return saved
    }

    /** 없는 낱말이면 아무것도 하지 않는다. */
    fun remove(
        operatorId: Long,
        termId: Long,
    ) {
        transaction.executeWithoutResult {
            if (terms.delete(termId)) {
                record(operatorId, ModerationActionType.REMOVE_TERM, termId, now())
            }
        }
        termCache.refreshNow()
    }

    private fun record(
        operatorId: Long,
        action: ModerationActionType,
        termId: Long,
        now: Instant,
    ) {
        actions.record(NewModerationAction(operatorId, action, TERM, termId, note = null, now))
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        const val TERM = "TERM"
        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 20
    }
}
