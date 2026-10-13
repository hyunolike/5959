package com.ogu.companion.application

import com.ogu.companion.domain.CompanionReactionRepository
import com.ogu.companion.domain.CompanionRepository
import com.ogu.companion.domain.Friend
import com.ogu.companion.domain.Reaction
import com.ogu.companion.domain.ReactionEntryRepository
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
import java.util.random.RandomGenerator

sealed interface ReactionClaim {
    data class Claimed(
        val reaction: Reaction,
    ) : ReactionClaim

    /** 올라온 지 너무 오래돼 더 시도하지 않고 닫았다. */
    data object Closed : ReactionClaim
}

/**
 * 반응 기록의 처리 일정을 다루는 짧은 쓰기들(009 research R4, 012 research R1~R4). AI 호출과 다른 모듈의 조회는 이 밖에서
 * 한다. 맡기 → 커밋 → 확인과 호출 → 결과 적기 순서다. 감정 분석의 `AnalysisStore`, 주간 리포트의 `WeeklyLetterStore`와
 * 같은 구조다. 글 하나에 기록 하나이고, 여러 친구가 반응할 때는 같은 기록의 다음 차례를 뒤로 미뤄 잡는다.
 * 로그에는 글 번호와 분류만 남긴다. 본문과 댓글은 남기지 않는다(FR-020).
 */
@Component
class ReactionStore(
    private val reactions: CompanionReactionRepository,
    private val entries: ReactionEntryRepository,
    private val properties: CompanionProperties,
    private val clock: Clock,
    aiProperties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random: RandomGenerator = RandomGenerator.getDefault()

    /** 맡은 동안 다른 실행기가 손대지 않을 시간: 댓글 쓰기와 위험 분류의 타임아웃 + 여유 10초. */
    private val lease: Duration = aiProperties.timeout.plus(aiProperties.riskTimeout).plus(LEASE_MARGIN)

    /** 한 차례에 맡는 글 수. */
    val batchSize: Int get() = properties.batchSize

    /** 기능이 켜져 있는가. 꺼져 있으면 주기 작업이 DB를 읽지도 않는다. */
    val enabled: Boolean get() = properties.enabled

    /** 한 글에 댓글을 청하는 횟수의 상한. */
    val maxAttempts: Int get() = properties.maxAttempts

    /**
     * 차례가 된 기록을 맡는다. [postId]를 주면 그 글만 본다. 맡을 것이 없으면 null이고, 올라온 지 `max-age`가 지났으면
     * 더 시도하지 않고 닫고 [ReactionClaim.Closed]다(FR-015). 뒤따르는 친구의 차례를 기다리던 기록도 여기서 닫힌다
     * (012 US2-AC5). 기능이 꺼져 있으면 아무것도 맡지 않는다(US5-AC1). 기록은 그대로 남아, 다시 켜면 그때 맡는다.
     */
    @Transactional
    fun claim(postId: Long? = null): ReactionClaim? {
        val now = now()
        val reaction = if (properties.enabled) reactions.lockDue(now, now.minus(properties.wait), postId) else null
        return when {
            reaction == null -> null
            !now.isBefore(reaction.postCreatedAt.plus(properties.maxAge)) -> {
                reactions.close(reaction.id, SkipReason.EXPIRED, now)
                log.info("올라온 지 오래된 글이라 반응하지 않습니다: postId={}", reaction.postId)
                ReactionClaim.Closed
            }
            else -> {
                reactions.defer(reaction.id, now.plus(lease))
                ReactionClaim.Claimed(reaction)
            }
        }
    }

    /**
     * 올라온 지 `max-age`가 지난, 아직 기다리는 기록을 한 번에 닫는다(FR-015). 닫은 수를 돌려준다. 주기 작업이 차례마다
     * 먼저 부른다. 기능이 오래 꺼져 있다 켜졌을 때 쌓인 기록을 하나씩 맡느라 새 글이 기다리지 않게 한다. 꺼져 있으면
     * 아무것도 닫지 않는다.
     */
    fun expireOld(): Int {
        if (!properties.enabled) return 0
        val now = now()
        val closed = reactions.expireOlderThan(now.minus(properties.maxAge), now)
        if (closed > 0) log.info("올라온 지 오래된 글의 반응 기록을 닫았습니다: {}건", closed)
        return closed
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
     * 못한 것이면 `FAILED`다. 뒤따르는 친구가 횟수를 채워도 닫는다. 남은 친구는 반응하지 않는다(012 FR-007).
     */
    fun recordFailure(
        reaction: Reaction,
        errorKind: String,
        rejected: Boolean,
    ) {
        val attempts = reaction.attempts + 1
        if (attempts >= properties.maxAttempts) {
            val reason = if (rejected) SkipReason.REJECTED else SkipReason.FAILED
            reactions.close(reaction.id, reason, now(), errorKind)
            log.warn("AI 친구가 반응하지 못하고 닫습니다: postId={}, reason={}, kind={}", reaction.postId, reason, errorKind)
        } else {
            reactions.recordFailure(reaction.id, errorKind)
            log.info("AI 친구의 댓글 실패, 재시도 예약: postId={}, kind={}, attempts={}", reaction.postId, errorKind, attempts)
        }
    }

    /**
     * 친구 한 명이 댓글과 공감을 남겼다고 적는다(012 research R3). 정해진 수를 다 채웠으면 닫고, 남았으면 다음 친구의
     * 차례를 앞 댓글에서 `follow-up`의 범위 안에서 무작위로 고른 간격 뒤로 잡는다. 같은 반응을 다시 적어도 수는 늘지 않는다
     * (남긴 뒤 적기 전에 멈췄던 기록을 맞출 때도 이 길이다). 적은 뒤의 기록을 돌려준다.
     */
    @Transactional
    fun recordReaction(
        reaction: Reaction,
        friend: Friend,
        commentId: Long,
    ): Reaction {
        val now = now()
        entries.insert(reaction, friend.id, commentId, now)
        val done = reactions.syncDone(reaction.id, commentId)
        if (done >= reaction.targetCount) {
            reactions.close(reaction.id, reason = null, now = now)
        } else {
            reactions.scheduleNext(reaction.id, now.plus(properties.followUp.gap(random)))
        }
        return reaction.copy(doneCount = done, attempts = 0)
    }

    /**
     * 기록을 닫는다. [reason]은 정해진 수를 채우지 못하고 멈춘 까닭이고, 다 채웠으면 null이다. 한 명 이상 반응했으면 `DONE`,
     * 아무도 반응하지 않았으면 `SKIPPED`다(012 research R4). 이미 남긴 댓글과 공감은 그대로다.
     */
    fun close(
        reaction: Reaction,
        reason: SkipReason?,
    ) {
        if (!reactions.close(reaction.id, reason, now())) {
            log.info("맡은 뒤 닫힌 기록이라 그대로 둡니다: postId={}", reaction.postId)
            return
        }
        log.info(
            "AI 친구의 반응을 닫습니다: postId={}, reason={}, done={}/{}",
            reaction.postId,
            reason ?: "FULL",
            reaction.doneCount,
            reaction.targetCount,
        )
    }

    /** 맡은 시간을 기다리지 않고 다음 주기에 다시 본다. 시도로 세지 않는다. */
    fun retrySoon(reaction: Reaction) {
        reactions.defer(reaction.id, now())
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        val LEASE_MARGIN: Duration = Duration.ofSeconds(10)
    }
}

/**
 * 글의 반응 기록을 만들고 반응할 친구의 수를 정한다(009 research R4, 012 research R2). 수는 글쓴이를 뺀 친구의 수를 넘지
 * 않는다. 같은 글의 기록이 이미 있으면 아무것도 바꾸지 않아, 이벤트가 다시 와도 처음 정한 수가 남는다.
 */
@Component
class ReactionEnroller(
    private val reactions: CompanionReactionRepository,
    private val companions: CompanionRepository,
    private val countPicker: ReactionCountPicker,
    private val clock: Clock,
) {
    fun enroll(event: PostCreated) {
        val others = companions.all().count { it.memberId != event.authorId }
        val count = countPicker.pick(event.postId).coerceIn(1, others.coerceIn(1, ReactionCount.MAX))
        reactions.enroll(
            postId = event.postId,
            postCreatedAt = event.createdAt.truncatedTo(ChronoUnit.MICROS),
            targetCount = count,
            now = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
    }
}

/**
 * 글이 커밋되면 반응 기록을 하나 만든다(009 research R4). 기능이 꺼져 있어도 만든다. 주기 작업만 켜고 끄기를 본다. 그래서
 * 껐다 켜면 그동안의 글 가운데 24시간 안의 것은 반응을 받는다(US5-AC4).
 */
@Component
class ReactionEnrollListener(
    private val enroller: ReactionEnroller,
) {
    @ApplicationModuleListener
    fun on(event: PostCreated) {
        enroller.enroll(event)
    }
}
