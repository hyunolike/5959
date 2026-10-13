package com.ogu.companion.application

import com.ogu.companion.domain.Friend
import com.ogu.companion.domain.Reaction
import com.ogu.companion.domain.SkipReason
import com.ogu.post.CompanionReactionCheck
import com.ogu.post.CompanionReactionOutcome
import com.ogu.post.CompanionReactionResult
import com.ogu.post.CompanionTarget
import com.ogu.post.PostCompanionApi
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 글 하나에 대한 AI 친구의 반응 한 번(009 research R4, 012 research R4): 맡기(짧은 트랜잭션) → 남아 있는 댓글과 기록
 * 맞추기 → 대상인지 확인 → 친구 고르기 → 하루의 몫 받기 → AI에 댓글 청하기와 검사(트랜잭션 밖) → 댓글과 공감 남기기(post의
 * 한 트랜잭션) → 결과 적기. 한 글에 여러 친구가 반응할 때는 친구마다 이 순서를 처음부터 다시 탄다. 한 번에 한 친구만
 * 남기고, 남은 수가 있으면 다음 차례를 뒤로 잡는다. AI가 실패해도 글쓰기와 사람의 댓글에는 영향이 없다(FR-013). 다시
 * 시도하는 일은 [ReactionScheduler]가 맡는다.
 */
@Component
class ReactionRunner(
    private val store: ReactionStore,
    private val eligibility: ReactionEligibility,
    private val budget: DailyBudget,
    private val picker: CompanionPicker,
    private val composer: CommentComposer,
    private val postApi: PostCompanionApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이 글이 차례면 한 번 맡는다. 맡았으면 true다. 기능이 꺼져 있으면 맡지 않는다. */
    fun attempt(postId: Long): Boolean {
        val claim = store.claim(postId)
        if (claim is ReactionClaim.Claimed) process(claim.reaction)
        return claim != null
    }

    /** 차례가 된 글을 `batch-size`개까지 먼저 올라온 것부터 하나씩 맡는다. 맡은 수를 돌려준다. 꺼져 있으면 0이다. */
    fun runDue(): Int {
        if (!store.enabled) return 0
        // 오래 꺼져 있다 켜졌으면 24시간이 지난 기록이 쌓여 있다. 먼저 한 번에 닫아 새 글이 그 뒤에서 기다리지 않게 한다
        store.expireOld()
        var handled = 0
        while (handled < store.batchSize) {
            val claim = store.claim() ?: break
            if (claim is ReactionClaim.Claimed) process(claim.reaction)
            handled++
        }
        return handled
    }

    private fun process(claimed: Reaction) {
        val friends = picker.all()
        val target = postApi.target(claimed.postId, friends.map { it.memberId })
        if (target == null) {
            store.close(claimed, SkipReason.GONE)
            return
        }
        val reaction = reconciled(claimed, target, friends)
        when {
            // 남긴 뒤 적기 전에 멈췄던 반응을 방금 적었다. 다 채웠으면 닫혔고, 아니면 다음 친구의 차례가 뒤로 잡혔다
            reaction !== claimed -> Unit
            reaction.full -> store.close(reaction, reason = null)
            else ->
                when (val verdict = eligibility.check(target)) {
                    is Eligibility.Closed -> store.close(reaction, verdict.reason)
                    Eligibility.Unsettled -> store.waitForRisk(reaction)
                    Eligibility.Eligible -> react(reaction, Posting(target, friends))
                }
        }
    }

    /**
     * 글에 남아 있는 AI 친구의 댓글 가운데 기록에 없는 것을 적는다(012 research R4의 3번). 글에 남은 댓글이 진실이고 기록이
     * 그것을 따라간다. 남긴 뒤 적기 전에 죽었거나, 맡은 시간이 지나 다른 실행기가 이어받았을 때 여기서 맞춰진다. 그 사이
     * 글이 숨겨졌어도 남긴 것은 남긴 것으로 적는다. 적은 것이 없으면 받은 기록을 그대로 돌려준다.
     */
    private fun reconciled(
        claimed: Reaction,
        target: CompanionTarget,
        friends: List<Friend>,
    ): Reaction {
        if (target.companionComments.size <= claimed.doneCount) return claimed
        var reaction = claimed
        target.companionComments.forEach { comment ->
            val friend = friends.firstOrNull { it.memberId == comment.memberId }
            if (friend != null) reaction = store.recordReaction(reaction, friend, comment.commentId)
        }
        return reaction
    }

    private fun react(
        reaction: Reaction,
        posting: Posting,
    ) {
        // 이 글에 이미 남긴 친구와 글쓴이는 고르지 않는다(012 FR-006)
        val friend = picker.pick(posting.taken)
        when {
            // 시도하다 죽어 횟수만 오르고 닫히지 못한 기록이다. 정해 둔 횟수를 넘겨 청하지 않는다
            reaction.attempts >= store.maxAttempts -> store.close(reaction, SkipReason.FAILED)
            // 남은 친구가 없다. 반응한 친구가 있으면 여기까지가 끝이다
            friend == null -> store.close(reaction, SkipReason.FAILED.takeIf { reaction.doneCount == 0 })
            else -> ask(reaction, friend, posting)
        }
    }

    private fun ask(
        reaction: Reaction,
        friend: Friend,
        posting: Posting,
    ) {
        // 몫이 떨어져 건너뛴 글은 다음 날 다시 보지 않는다. 밀린 글이 다음 날의 몫을 먼저 쓰지 않게 한다
        val day = budget.reserveDay()
        if (day == null) {
            store.close(reaction, SkipReason.DAILY_LIMIT)
            return
        }
        store.beginAttempt(reaction, friend)
        when (val draft = composer.compose(posting.target, friend.voice)) {
            is Draft.Written -> leave(reaction, friend, draft.comment, posting)
            is Draft.Failed -> onFailure(reaction, draft, day)
        }
    }

    private fun onFailure(
        reaction: Reaction,
        failed: Draft.Failed,
        reservedOn: LocalDate,
    ) {
        if (failed.notSent) {
            // 회로가 열려 호출이 나가지 않았다. 받은 날의 몫을 돌려주고 시도로 세지 않는다
            budget.release(reservedOn)
            store.revertAttempt(reaction, failed.kind)
        } else {
            store.recordFailure(reaction, failed.kind, failed.rejected)
        }
    }

    private fun leave(
        reaction: Reaction,
        friend: Friend,
        comment: String,
        posting: Posting,
    ) {
        val result = posted(reaction, friend, comment, posting) ?: return
        when (result.outcome) {
            CompanionReactionOutcome.GONE -> store.close(reaction, SkipReason.GONE)
            CompanionReactionOutcome.HUMAN_COMMENTED -> store.close(reaction, SkipReason.HUMAN_COMMENT)
            // 쓰는 사이에 본문이 고쳐졌다. 옛 본문에 맞춘 댓글은 버리고, 고친 글의 판정이 끝난 뒤 다시 본다
            CompanionReactionOutcome.STALE -> store.recordFailure(reaction, STALE, rejected = false)
            // 다른 실행기가 먼저 채웠다. 아무것도 남기지 않았다. 남아 있는 댓글로 기록을 맞춘다
            CompanionReactionOutcome.FULL -> settle(reaction, posting.friends)
            // 이 친구가 이미 남겼어도 그 댓글로 적는다. 한 글에 같은 친구의 댓글은 하나다
            CompanionReactionOutcome.REACTED, CompanionReactionOutcome.ALREADY_REACTED ->
                store.recordReaction(reaction, friend, checkNotNull(result.commentId) { "남긴 댓글의 번호가 없습니다" })
        }
    }

    /** 글에 남아 있는 댓글로 기록을 맞춘다. 맞출 것이 없으면 다음 주기에 다시 본다. */
    private fun settle(
        reaction: Reaction,
        friends: List<Friend>,
    ) {
        val target = postApi.target(reaction.postId, friends.map { it.memberId })
        when {
            target == null -> store.close(reaction, SkipReason.GONE)
            reconciled(reaction, target, friends) === reaction -> store.retrySoon(reaction)
        }
    }

    /** 댓글과 공감을 남긴다. 남기지 못해 결과를 이미 적었으면 null이다. */
    @Suppress("TooGenericExceptionCaught") // 남기기의 어떤 실패도 재시도 일정으로 넘긴다
    private fun posted(
        reaction: Reaction,
        friend: Friend,
        comment: String,
        posting: Posting,
    ): CompanionReactionResult? =
        try {
            // 대상을 읽을 때의 본문 그대로이고, AI 친구의 댓글이 정해진 수보다 적을 때만 남긴다
            postApi.react(
                postId = reaction.postId,
                memberId = friend.memberId,
                content = comment,
                companionIds = posting.memberIds,
                check = CompanionReactionCheck(posting.target.contentVersion, reaction.targetCount),
            )
        } catch (e: BusinessException) {
            // 확인한 뒤에 글이 지워졌다(댓글 수를 올리다 알았다)
            if (e.errorCode == ErrorCode.POST_NOT_FOUND) {
                store.close(reaction, SkipReason.GONE)
            } else {
                store.recordFailure(reaction, "POST_${e.errorCode.name}", e.errorCode == ErrorCode.INVALID_REQUEST)
            }
            null
        } catch (e: RuntimeException) {
            log.warn("AI 친구의 댓글을 남기지 못했습니다: postId={}, cause={}", reaction.postId, e.javaClass.simpleName)
            store.recordFailure(reaction, POST_ERROR, rejected = false)
            null
        }

    /** 남길 때 다시 견줄 것: 대상을 읽을 때의 글과 AI 친구들. */
    private data class Posting(
        val target: CompanionTarget,
        val friends: List<Friend>,
    ) {
        val memberIds: List<Long> get() = friends.map { it.memberId }

        /** 이 글에 반응할 수 없는 회원: 이미 댓글을 남긴 친구와 글쓴이. */
        val taken: Set<Long> get() = target.companionComments.map { it.memberId }.toSet() + target.authorId
    }

    private companion object {
        const val POST_ERROR = "POST_ERROR"
        const val STALE = "STALE"
    }
}

/**
 * `poll-interval`(30초)마다 차례가 된 글을 맡는다. 여러 인스턴스가 돌아도 `FOR UPDATE SKIP LOCKED`와 맡을 때 미뤄 두는
 * 다음 차례 덕분에 같은 글을 두 번 맡지 않고, 한 글에 같은 친구는 한 번을 유일 제약이 지킨다(012 research R1). 기능이 꺼져 있으면
 * 아무것도 맡지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.companion", name = ["scheduler-enabled"], matchIfMissing = true)
class ReactionScheduler(
    private val runner: ReactionRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("TooGenericExceptionCaught") // 한 번 실패해도 다음 주기에 다시 돈다
    @Scheduled(fixedDelayString = "\${ogu.companion.poll-interval:30s}")
    fun poll() {
        try {
            val handled = runner.runDue()
            if (handled > 0) log.info("AI 친구의 반응 처리: {}건", handled)
        } catch (e: RuntimeException) {
            log.error("AI 친구의 반응 주기가 실패했습니다", e)
        }
    }
}
