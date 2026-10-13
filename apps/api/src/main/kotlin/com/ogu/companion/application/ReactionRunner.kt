package com.ogu.companion.application

import com.ogu.companion.domain.Friend
import com.ogu.companion.domain.Reaction
import com.ogu.companion.domain.SkipReason
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

/**
 * 글 하나에 대한 AI 친구의 반응 한 번(009 research R4): 맡기(짧은 트랜잭션) → 대상인지 확인 → 하루의 몫 받기 → AI에 댓글
 * 청하기와 검사(트랜잭션 밖) → 댓글과 공감 남기기(post의 한 트랜잭션) → 결과 적기. AI가 실패해도 글쓰기와 사람의 댓글에는
 * 영향이 없다(FR-013). 다시 시도하는 일은 [ReactionScheduler]가 맡는다.
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

    private fun process(reaction: Reaction) {
        val memberIds = picker.all().map { it.memberId }
        when (val verdict = eligibility.check(reaction.postId, memberIds)) {
            is Eligibility.Closed -> store.skip(reaction, verdict.reason)
            is Eligibility.AlreadyReacted -> store.complete(reaction, verdict.commentId)
            Eligibility.Unsettled -> store.waitForRisk(reaction)
            is Eligibility.Eligible -> react(reaction, verdict.target, memberIds)
        }
    }

    private fun react(
        reaction: Reaction,
        target: CompanionTarget,
        memberIds: List<Long>,
    ) {
        // 몫이 떨어져 건너뛴 글은 다음 날 다시 보지 않는다. 밀린 글이 다음 날의 몫을 먼저 쓰지 않게 한다
        if (!budget.reserve()) {
            store.skip(reaction, SkipReason.DAILY_LIMIT)
            return
        }
        val friend = picker.pick()
        store.beginAttempt(reaction, friend)
        when (val draft = composer.compose(target, friend.voice)) {
            is Draft.Written -> leave(reaction, friend, draft.comment, memberIds)
            is Draft.Failed -> onFailure(reaction, draft)
        }
    }

    private fun onFailure(
        reaction: Reaction,
        failed: Draft.Failed,
    ) {
        if (failed.notSent) {
            // 회로가 열려 호출이 나가지 않았다. 몫을 돌려주고 시도로 세지 않는다
            budget.release()
            store.revertAttempt(reaction, failed.kind)
        } else {
            store.recordFailure(reaction, failed.kind, failed.rejected)
        }
    }

    private fun leave(
        reaction: Reaction,
        friend: Friend,
        comment: String,
        memberIds: List<Long>,
    ) {
        val result = posted(reaction, friend, comment, memberIds) ?: return
        val commentId = result.commentId
        when (result.outcome) {
            CompanionReactionOutcome.GONE -> store.skip(reaction, SkipReason.GONE)
            CompanionReactionOutcome.HUMAN_COMMENTED -> store.skip(reaction, SkipReason.HUMAN_COMMENT)
            // 다른 실행기가 먼저 남겼어도 그 댓글로 닫는다. 댓글은 하나다
            CompanionReactionOutcome.REACTED, CompanionReactionOutcome.ALREADY_REACTED ->
                store.complete(reaction, checkNotNull(commentId) { "남긴 댓글의 번호가 없습니다" })
        }
    }

    /** 댓글과 공감을 남긴다. 남기지 못해 결과를 이미 적었으면 null이다. */
    @Suppress("TooGenericExceptionCaught") // 남기기의 어떤 실패도 재시도 일정으로 넘긴다
    private fun posted(
        reaction: Reaction,
        friend: Friend,
        comment: String,
        memberIds: List<Long>,
    ): CompanionReactionResult? =
        try {
            postApi.react(reaction.postId, friend.memberId, comment, memberIds)
        } catch (e: BusinessException) {
            // 확인한 뒤에 글이 지워졌다(댓글 수를 올리다 알았다)
            if (e.errorCode == ErrorCode.POST_NOT_FOUND) {
                store.skip(reaction, SkipReason.GONE)
            } else {
                store.recordFailure(reaction, "POST_${e.errorCode.name}", e.errorCode == ErrorCode.INVALID_REQUEST)
            }
            null
        } catch (e: RuntimeException) {
            log.warn("AI 친구의 댓글을 남기지 못했습니다: postId={}, cause={}", reaction.postId, e.javaClass.simpleName)
            store.recordFailure(reaction, POST_ERROR, rejected = false)
            null
        }

    private companion object {
        const val POST_ERROR = "POST_ERROR"
    }
}

/**
 * `poll-interval`(30초)마다 차례가 된 글을 맡는다. 여러 인스턴스가 돌아도 `FOR UPDATE SKIP LOCKED`와 맡을 때 미뤄 두는
 * 다음 차례 덕분에 같은 글을 두 번 맡지 않고, 한 글에 한 번은 유일 제약이 지킨다(research R4). 기능이 꺼져 있으면
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
