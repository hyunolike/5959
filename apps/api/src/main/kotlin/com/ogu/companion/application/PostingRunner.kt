package com.ogu.companion.application

import com.ogu.companion.domain.CompanionDueRepository
import com.ogu.companion.domain.CompanionRepository
import com.ogu.companion.domain.Friend
import com.ogu.companion.domain.GiveUpReason
import com.ogu.companion.domain.PostAttempt
import com.ogu.post.CommentTone
import com.ogu.post.CompanionPostApi
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.schedule.DueJob
import com.ogu.shared.schedule.DueTimer
import com.ogu.shared.schedule.DueTimers
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

/**
 * AI 친구의 글쓰기 한 번(012 research R12): 쓰는 중인 기록을 맡거나, 조건을 채우면 새 기록을 열어 맡는다 → 이미 올렸는지와
 * 조건이 그대로인지 확인 → AI에 글 청하기와 검사(트랜잭션 밖) → 글 저장하기(post의 한 트랜잭션) → 결과 적기. 한 번에 글
 * 하나만 쓴다. AI가 실패해도 회원의 글쓰기와 읽기에는 영향이 없다(FR-035). 다시 시도하는 일은 [PostingScheduler]가 맡는다.
 */
@Component
class PostingRunner(
    private val store: PostingStore,
    private val planner: PostingPlanner,
    private val composer: PostComposer,
    private val companions: CompanionRepository,
    private val postApi: CompanionPostApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 쓸 것이 있으면 한 번 한다. 기록 하나를 맡았으면 true다. 글쓰기가 꺼져 있으면 아무것도 하지 않는다. */
    fun runOnce(): Boolean {
        if (!store.enabled) return false
        val attempt = store.claim() ?: opened()
        attempt?.let(::write)
        return attempt != null
    }

    /** 조건을 채우면 새 기록을 열고 맡는다. 다른 실행기가 먼저 열었으면 그쪽이 맡는다. */
    private fun opened(): PostAttempt? {
        val plan = planner.next() ?: return null
        return if (store.open(plan.friend, plan.topic, plan.tone)) store.claim() else null
    }

    private fun write(attempt: PostAttempt) {
        val friend = companions.all().firstOrNull { it.id == attempt.companionId }
        // 저장한 뒤 기록을 적기 전에 멈췄던 경우다. 같은 글을 두 번 올리지 않는다
        val written = friend?.let { postApi.findWrittenSince(it.memberId, attempt.createdAt) }
        val stop = planner.stopReason(attempt)
        when {
            friend == null -> store.giveUp(attempt, GiveUpReason.FAILED)
            written != null -> store.posted(attempt, written)
            stop != null -> store.giveUp(attempt, stop)
            else -> ask(attempt, friend)
        }
    }

    private fun ask(
        attempt: PostAttempt,
        friend: Friend,
    ) {
        store.beginAttempt(attempt)
        when (val composed = composer.compose(attempt, friend.voice)) {
            is Composed.Written -> save(attempt, friend, composed.content)
            is Composed.Failed -> store.recordFailure(attempt, composed)
        }
    }

    @Suppress("TooGenericExceptionCaught") // 저장의 어떤 실패도 재시도 일정으로 넘긴다
    private fun save(
        attempt: PostAttempt,
        friend: Friend,
        content: String,
    ) {
        try {
            val postId = postApi.write(friend.memberId, content, CommentTone.valueOf(attempt.commentTone))
            store.posted(attempt, postId)
        } catch (e: BusinessException) {
            // 본문이 글의 규칙에 맞지 않는다. 검사에 걸린 것으로 센다
            val rejected = e.errorCode == ErrorCode.INVALID_REQUEST
            store.recordFailure(attempt, Composed.Failed("POST_${e.errorCode.name}", rejected = rejected))
        } catch (e: RuntimeException) {
            log.warn("AI 친구의 글을 저장하지 못했습니다: id={}, cause={}", attempt.id, e.javaClass.simpleName)
            store.recordFailure(attempt, Composed.Failed(POST_ERROR))
        }
    }

    private companion object {
        const val POST_ERROR = "POST_ERROR"
    }
}

/**
 * 글을 쓸 때인지 본다. 018 research R3부터는 1분마다 DB를 읽지 않고, 계산한 시각에만 깨어난다. 할 일이 없으면 DB에 묻지
 * 않으므로 Neon이 잠들 수 있다.
 *
 * - 쓰는 중인 기록이 있으면 그 다음 차례(맡기의 임대, 실패의 백오프)에 깬다.
 * - 없으면 새 기록을 열어 볼 때([PostingPlanner.nextOpening])에 깬다: 가장 새 글 + `quiet`, 시간대 밖이면 그날 또는 다음
 *   날의 `active-from`, 그날 쓸 몫이 없으면 다음 날의 `active-from`이다.
 * - 새 글이 오면 조용한 시간을 다시 세야 하므로 [ReactionEnrollListener]가 커밋 뒤에 [rearmAfterCommit]을 부른다. 걸린 시각은
 *   이를 때만 바뀌므로 [DueTimer.arm]이 아닌 [DueTimer.rearm]으로 다시 읽는다.
 * - 여러 인스턴스가 돌아도 쓰는 중인 기록은 하나이고(부분 유일 색인) `FOR UPDATE SKIP LOCKED`로 한 인스턴스만 맡는다
 *   (012 research R12). 글쓰기가 꺼져 있으면 걸지 않고 DB도 읽지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "ogu.companion.posting", name = ["scheduler-enabled"], matchIfMissing = true)
class PostingScheduler(
    private val runner: PostingRunner,
    private val planner: PostingPlanner,
    private val due: CompanionDueRepository,
    private val properties: CompanionProperties,
    timers: DueTimers,
) : DueJob {
    private val timer = timers.register(this)

    override val name = "AI 친구의 글쓰기"

    override fun nextDue(): Instant? {
        if (!properties.posting.enabled) return null
        return due.earliestWriting() ?: planner.nextOpening()
    }

    override fun runDue() {
        runner.runOnce()
    }

    /** 트랜잭션 안이면 커밋된 뒤에, 아니면 바로 다음 시각을 다시 읽는다. 글쓰기가 꺼져 있으면 아무것도 하지 않는다. */
    fun rearmAfterCommit() {
        if (!properties.posting.enabled) return
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            timer.rearm()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = timer.rearm()
            },
        )
    }

    /** 지금 걸린 다음 시각. 테스트와 로그용이다. */
    val nextAt: Instant? get() = timer.nextAt
}
