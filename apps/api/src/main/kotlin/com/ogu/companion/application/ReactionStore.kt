package com.ogu.companion.application

import com.ogu.companion.domain.CompanionReactionRepository
import com.ogu.companion.domain.Friend
import com.ogu.companion.domain.Reaction
import com.ogu.companion.domain.SkipReason
import com.ogu.post.PostCreated
import com.ogu.shared.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

sealed interface ReactionClaim {
    data class Claimed(
        val reaction: Reaction,
    ) : ReactionClaim

    /** 올라온 지 너무 오래돼 시도 없이 닫았다. */
    data object Closed : ReactionClaim
}

/**
 * 반응 기록의 처리 일정을 다루는 짧은 쓰기들(009 research R4). AI 호출과 다른 모듈의 조회는 이 밖에서 한다. 맡기 → 커밋 →
 * 확인과 호출 → 결과 적기 순서다. 감정 분석의 `AnalysisStore`, 주간 리포트의 `WeeklyLetterStore`와 같은 구조다.
 * 로그에는 글 번호와 분류만 남긴다. 본문과 댓글은 남기지 않는다(FR-020).
 */
@Component
class ReactionStore(
    private val reactions: CompanionReactionRepository,
    private val properties: CompanionProperties,
    private val clock: Clock,
    aiProperties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 맡은 동안 다른 실행기가 손대지 않을 시간: 댓글 쓰기와 위험 분류의 타임아웃 + 여유 10초. */
    private val lease: Duration = aiProperties.timeout.plus(aiProperties.riskTimeout).plus(LEASE_MARGIN)

    /** 한 차례에 맡는 글 수. */
    val batchSize: Int get() = properties.batchSize

    /**
     * 차례가 된 기록을 맡는다. [postId]를 주면 그 글만 본다. 맡을 것이 없으면 null이고, 올라온 지 `max-age`가 지났으면
     * 시도 없이 닫고 [ReactionClaim.Closed]다(FR-015). 기능이 꺼져 있으면 아무것도 맡지 않는다(US5-AC1). 기록은 그대로
     * 남아, 다시 켜면 그때 맡는다.
     */
    @Transactional
    fun claim(postId: Long? = null): ReactionClaim? {
        val now = now()
        val reaction = if (properties.enabled) reactions.lockDue(now, now.minus(properties.wait), postId) else null
        return when {
            reaction == null -> null
            !now.isBefore(reaction.postCreatedAt.plus(properties.maxAge)) -> {
                reactions.skip(reaction.id, SkipReason.EXPIRED, now)
                log.info("올라온 지 오래된 글이라 반응하지 않습니다: postId={}", reaction.postId)
                ReactionClaim.Closed
            }
            else -> {
                reactions.defer(reaction.id, now.plus(lease))
                ReactionClaim.Claimed(reaction)
            }
        }
    }

    /** 글의 위험 판정이 끝나지 않았다. 조금 뒤에 다시 본다. 시도로 세지 않는다(US3-AC3). */
    fun waitForRisk(reaction: Reaction) {
        reactions.defer(reaction.id, now().plus(properties.riskWait))
    }

    /** AI에 댓글을 청하기 직전에 적는다. 실패하면 간격을 늘려 다시 맡게 다음 차례를 미뤄 둔다. */
    fun beginAttempt(
        reaction: Reaction,
        friend: Friend,
    ) {
        val delay = maxOf(properties.retry.delayAfter(reaction.attempts + 1), lease)
        reactions.markAttempt(reaction.id, friend.id, now().plus(delay))
    }

    /** 호출이 공급자에 나가지 않았다. 시도로 세지 않고 다음 차례에 다시 본다. */
    fun revertAttempt(
        reaction: Reaction,
        errorKind: String,
    ) {
        reactions.revertAttempt(reaction.id, errorKind)
        log.info("AI 친구의 댓글을 청하지 못했습니다: postId={}, kind={}", reaction.postId, errorKind)
    }

    /**
     * 이번 시도가 실패했다. 정해 둔 횟수를 채웠으면 닫는다. 답이 검사에 걸린 것([rejected])이면 `REJECTED`, 답을 받지
     * 못한 것이면 `FAILED`다.
     */
    fun recordFailure(
        reaction: Reaction,
        errorKind: String,
        rejected: Boolean,
    ) {
        val attempts = reaction.attempts + 1
        if (attempts >= properties.maxAttempts) {
            val reason = if (rejected) SkipReason.REJECTED else SkipReason.FAILED
            reactions.skip(reaction.id, reason, now(), errorKind)
            log.warn("AI 친구가 반응하지 못하고 닫습니다: postId={}, reason={}, kind={}", reaction.postId, reason, errorKind)
        } else {
            reactions.recordFailure(reaction.id, errorKind)
            log.info("AI 친구의 댓글 실패, 재시도 예약: postId={}, kind={}, attempts={}", reaction.postId, errorKind, attempts)
        }
    }

    fun complete(
        reaction: Reaction,
        commentId: Long,
    ) {
        if (!reactions.complete(reaction.id, commentId, now())) {
            log.info("맡은 뒤 닫힌 기록이라 그대로 둡니다: postId={}", reaction.postId)
        }
    }

    fun skip(
        reaction: Reaction,
        reason: SkipReason,
    ) {
        reactions.skip(reaction.id, reason, now())
        log.info("AI 친구가 반응하지 않습니다: postId={}, reason={}", reaction.postId, reason)
    }

    /** 글이 저장되면 반응 기록을 만든다. 같은 이벤트가 다시 와도 기록은 하나다. */
    fun enroll(event: PostCreated) {
        reactions.enroll(event.postId, event.createdAt.truncatedTo(ChronoUnit.MICROS), now())
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        val LEASE_MARGIN: Duration = Duration.ofSeconds(10)
    }
}

/**
 * 글이 커밋되면 반응 기록을 하나 만든다(009 research R4). 기능이 꺼져 있어도 만든다. 주기 작업만 켜고 끄기를 본다. 그래서
 * 껐다 켜면 그동안의 글 가운데 24시간 안의 것은 반응을 받는다(US5-AC4).
 */
@Component
class ReactionEnrollListener(
    private val store: ReactionStore,
) {
    @ApplicationModuleListener
    fun on(event: PostCreated) {
        store.enroll(event)
    }
}
