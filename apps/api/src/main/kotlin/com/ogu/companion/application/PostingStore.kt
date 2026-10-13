package com.ogu.companion.application

import com.ogu.companion.domain.CompanionPostRepository
import com.ogu.companion.domain.CompanionRepository
import com.ogu.companion.domain.Friend
import com.ogu.companion.domain.GiveUpReason
import com.ogu.companion.domain.PostAttempt
import com.ogu.post.CommentTone
import com.ogu.post.CompanionPostApi
import com.ogu.shared.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Random
import java.util.random.RandomGenerator

/**
 * 글 기록의 처리 일정을 다루는 짧은 쓰기들(012 research R12). AI 호출과 다른 모듈의 조회는 이 밖에서 한다. 맡기 → 커밋 →
 * 확인과 호출 → 결과 적기 순서다. 반응의 [ReactionStore]와 같은 구조다. 로그에는 기록 번호와 분류만 남긴다. 본문은 남기지
 * 않는다(FR-037).
 */
@Component
class PostingStore(
    private val posts: CompanionPostRepository,
    private val properties: CompanionProperties,
    private val clock: Clock,
    aiProperties: AiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 맡은 동안 다른 실행기가 손대지 않을 시간: 글쓰기와 위험 분류의 타임아웃 + 여유 10초. */
    private val lease: Duration = aiProperties.timeout.plus(aiProperties.riskTimeout).plus(LEASE_MARGIN)

    /** 글쓰기가 켜져 있는가. 꺼져 있으면 주기 작업이 DB를 읽지도 않는다. */
    val enabled: Boolean get() = properties.posting.enabled

    /**
     * 차례가 된 쓰는 중인 기록을 맡는다. 맡을 것이 없으면 null이다. 글쓰기가 꺼져 있으면 아무것도 맡지 않는다. 기록은 그대로
     * 남아, 다시 켜면 그때 조건을 다시 본다.
     */
    @Transactional
    fun claim(): PostAttempt? {
        if (!enabled) return null
        val now = now()
        return posts.lockWriting(now)?.also { posts.defer(it.id, now.plus(lease)) }
    }

    /**
     * [friend]가 [topic]으로 글을 쓴다는 기록을 연다. 그 친구의 오늘 기록이 이미 있거나 쓰는 중인 기록이 이미 있으면 열지
     * 못하고 false다. 여러 서버가 함께 열어도 하나만 열린다.
     */
    fun open(
        friend: Friend,
        topic: PostTopic,
        tone: CommentTone,
    ): Boolean {
        val now = now()
        val opened = posts.open(friend.id, now.atZone(SEOUL).toLocalDate(), topic.key, tone.name, now)
        if (opened) log.info("AI 친구가 글을 쓰기 시작합니다: companion={}, topic={}", friend.id, topic.key)
        return opened
    }

    /** AI에 글을 청하기 직전에 적는다. 실패하면 간격을 늘려 다시 맡게 다음 차례를 미뤄 둔다. */
    fun beginAttempt(attempt: PostAttempt) {
        val delay = maxOf(properties.retry.delayAfter(attempt.attempts + 1), lease)
        posts.defer(attempt.id, now().plus(delay), counted = true)
    }

    /**
     * 이번 시도가 실패했다. 호출이 공급자에 나가지 않았으면 시도로 세지 않는다. 정해 둔 횟수를 채웠으면 닫는다. 답이 검사에
     * 걸린 것이면 `REJECTED`, 답을 받지 못한 것이면 `FAILED`다.
     */
    fun recordFailure(
        attempt: PostAttempt,
        failed: Composed.Failed,
    ) {
        val exhausted = !failed.notSent && attempt.attempts + 1 >= properties.posting.maxAttempts
        if (exhausted) {
            val reason = if (failed.rejected) GiveUpReason.REJECTED else GiveUpReason.FAILED
            posts.giveUp(attempt.id, reason, now(), failed.kind)
            log.warn("AI 친구가 글을 쓰지 못하고 닫습니다: id={}, reason={}, kind={}", attempt.id, reason, failed.kind)
        } else {
            posts.recordFailure(attempt.id, failed.kind, counted = !failed.notSent)
            log.info("AI 친구의 글쓰기 실패, 재시도 예약: id={}, kind={}", attempt.id, failed.kind)
        }
    }

    fun posted(
        attempt: PostAttempt,
        postId: Long,
    ) {
        posts.posted(attempt.id, postId, now())
        log.info("AI 친구가 글을 올렸습니다: id={}, companion={}, postId={}", attempt.id, attempt.companionId, postId)
    }

    fun giveUp(
        attempt: PostAttempt,
        reason: GiveUpReason,
    ) {
        posts.giveUp(attempt.id, reason, now())
        log.info("AI 친구가 글을 쓰지 않습니다: id={}, reason={}", attempt.id, reason)
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private val LEASE_MARGIN: Duration = Duration.ofSeconds(10)
    }
}

/**
 * 지금 AI 친구가 글을 쓸 때인지 본다(012 research R12, FR-021). 모두 읽기다. 조건을 채우면 누가, 어떤 소재로, 어떤 댓글
 * 말투로 쓸지 고른다. 쓰는 중인 기록도 차례마다 여기서 조건을 다시 본다.
 */
@Component
class PostingPlanner(
    private val posts: CompanionPostRepository,
    private val companions: CompanionRepository,
    private val postApi: CompanionPostApi,
    private val properties: CompanionProperties,
    private val clock: Clock,
) {
    // 운영 이미지의 JRE(eclipse-temurin의 jre)에는 `RandomGenerator.getDefault()`의 알고리즘이 든 jdk.random 모듈이 없다
    private val random: RandomGenerator = Random()
    private val posting get() = properties.posting

    /**
     * 새 글을 쓸 때면 쓸 것을, 아니면 null을 준다. 싼 확인부터 본다: 쓰는 중인 기록이 없다, 시간대 안이다, 그날 올린 글과
     * 청한 횟수가 남았다, 오늘 쓰지 않은 친구가 있다, 한동안 보이는 새 글이 없었다.
     */
    fun next(): Plan? {
        val now = koreaNow()
        val day = posts.dayOf(now.toLocalDate())
        val free = companions.all().filter { it.id !in day.companionIds }
        val ready =
            !posts.hasWriting() &&
                posting.activeAt(now.toLocalTime()) &&
                day.posted < posting.dailyPosts &&
                day.requests < posting.dailyRequests &&
                free.isNotEmpty() &&
                quiet(now.toInstant())
        return if (ready) Plan(longestRested(free), freshTopic(), anyTone()) else null
    }

    /** 쓰는 중인 [attempt]를 그만둘 까닭. 계속 써도 되면 null이다. */
    fun stopReason(attempt: PostAttempt): GiveUpReason? {
        val now = koreaNow()
        return when {
            // 오래 꺼져 있었다. 밀린 글을 몰아서 쓰지 않는다
            attempt.day != now.toLocalDate() -> GiveUpReason.EXPIRED
            !posting.activeAt(now.toLocalTime()) -> GiveUpReason.OFF_HOURS
            !quiet(now.toInstant()) -> GiveUpReason.NOT_QUIET
            // 시도하다 죽어 횟수만 오르고 닫히지 못한 기록이다. 정해 둔 횟수를 넘겨 청하지 않는다
            attempt.attempts >= posting.maxAttempts -> GiveUpReason.FAILED
            posts.dayOf(attempt.day).requests >= posting.dailyRequests -> GiveUpReason.DAILY_LIMIT
            else -> null
        }
    }

    /** 회원에게 보이는 가장 새 글이 `quiet`보다 오래됐거나 글이 없다. 사람의 글과 AI 친구의 글을 가리지 않는다. */
    private fun quiet(now: Instant): Boolean {
        val latest = postApi.latestVisiblePostAt() ?: return true
        return !latest.isAfter(now.minus(posting.quiet))
    }

    /** 글을 가장 오래 쉰 친구. 올린 적이 없는 친구가 먼저다. 반응의 차례와는 따로 센다. */
    private fun longestRested(free: List<Friend>): Friend {
        val lastPosted = posts.lastPostedByCompanion()
        return free.minWith(compareBy<Friend> { lastPosted[it.id] ?: NEVER }.thenBy { it.id })
    }

    /** 최근 기록들이 쓴 소재를 뺀 것 가운데 하나. 다 썼으면 아무것이나 고른다. */
    private fun freshTopic(): PostTopic {
        val recent = posts.recentTopics(posting.recentTopics).toSet()
        val fresh = PostTopics.ALL.filter { it.key !in recent }.ifEmpty { PostTopics.ALL }
        return fresh[random.nextInt(fresh.size)]
    }

    private fun anyTone(): CommentTone = CommentTone.entries[random.nextInt(CommentTone.entries.size)]

    private fun koreaNow(): ZonedDateTime = clock.instant().atZone(PostingStore.SEOUL)

    /** 쓸 글 하나: 누가, 어떤 소재로, 어떤 댓글 말투로. */
    data class Plan(
        val friend: Friend,
        val topic: PostTopic,
        val tone: CommentTone,
    )

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
