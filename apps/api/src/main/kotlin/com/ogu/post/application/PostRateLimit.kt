package com.ogu.post.application

import com.ogu.post.domain.PostRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

@ConfigurationProperties("ogu.post.rate-limit")
data class PostRateLimitProperties(
    val maxPerHour: Int = 10,
)

/**
 * 글 작성 제한(FR-018, research R9). 회원마다 최근 1시간 안에 쓴 글(지운 글 포함)이 [PostRateLimitProperties.maxPerHour]개면
 * `429 POST_RATE_LIMITED`로 거절한다. 같은 회원의 동시 요청이 함께 통과하지 않도록 회원 단위 advisory lock을 잡고 센다.
 * 잠금은 두 정수 키 형식이고 이름공간이 글 단위 잠금(`PostLock`)과 달라 키 공간이 겹치지 않는다.
 */
@Component
class PostRateLimit(
    private val postRepository: PostRepository,
    private val jdbcClient: JdbcClient,
    private val properties: PostRateLimitProperties,
) {
    fun check(
        authorId: Long,
        now: Instant,
    ) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "작성 제한은 트랜잭션 안에서 검사합니다." }
        jdbcClient
            .sql("select pg_advisory_xact_lock(:namespace, :key)")
            .param("namespace", LOCK_NAMESPACE)
            .param("key", (authorId xor (authorId ushr Int.SIZE_BITS)).toInt())
            .query(RowCallbackHandler { })

        val since = now.minus(WINDOW)
        val count = postRepository.countByAuthorIdAndCreatedAtAfter(authorId, since)
        if (count < properties.maxPerHour) return

        val oldest = postRepository.findFirstByAuthorIdAndCreatedAtAfterOrderByCreatedAtAsc(authorId, since)
        val retryAfter = oldest?.let { Duration.between(now, it.createdAt.plus(WINDOW)) } ?: WINDOW
        throw BusinessException(
            ErrorCode.POST_RATE_LIMITED,
            retryAfterSeconds = retryAfter.toSeconds().coerceAtLeast(1).toInt(),
        )
    }

    private companion object {
        val WINDOW: Duration = Duration.ofHours(1)

        /** advisory lock 두 정수 키 중 첫째. 작성 제한 전용 값이다. */
        const val LOCK_NAMESPACE = 0x706f7374 // "post"
    }
}
