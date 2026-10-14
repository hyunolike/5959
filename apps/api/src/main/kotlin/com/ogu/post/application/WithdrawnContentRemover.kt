package com.ogu.post.application

import com.ogu.shared.lock.PostLock
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Instant

/**
 * 탈퇴한 회원의 글과 댓글을 지운 것으로 바꾼다(013 research R2, R3). 새 상태를 만들지 않고 회원이 스스로 지울 때와 같은
 * `deleted_at`을 적는다. 그래서 피드, 추천, 도감, 통계, 알림 미리보기가 고치지 않아도 지운 글로 본다.
 *
 * 글과 댓글이 몇 개든 문장은 셋이다(SC-006). 글마다 [PostService.delete]를 부르지 않는다.
 *
 * - 댓글은 지금의 지우기 규칙과 같다([CommentService.delete]). 내 원 댓글 아래의 살아 있는 답글도 함께 지우고, 살아 있는
 *   글의 댓글 수를 실제로 지운 만큼 줄인다. 지운 글의 댓글 수는 건드리지 않는다.
 * - 다시 불러도 결과가 같다. `deleted_at is null`인 행만 잡으므로 댓글 수가 두 번 줄지 않는다(US5-AC4). 커밋 뒤 정리가
 *   탈퇴와 겹쳐 들어온 것을 잡으려고 한 번 더 부른다(research R5).
 * - 잠금은 다른 쓰기와 같은 순서다. `comments`, `posts` 행을 먼저 잠그고 글 잠금([PostLock])을 나중에 잡는다.
 * - 탈퇴끼리는 맨 먼저 잠금 하나로 줄을 선다. 한 문장이 여러 글과 댓글의 행을 잡는 순서는 정해져 있지 않아, 서로의 글에
 *   댓글을 단 두 회원이 함께 탈퇴하면 교착이 날 수 있다. 탈퇴는 드문 일이라 줄을 세워도 느려지지 않는다.
 *
 * `PostRemoved`, `CommentRemoved`는 여기서 내지 않는다. [WithdrawnContentCleaner]가 커밋 뒤에 나눠서 낸다.
 */
@Component
class WithdrawnContentRemover(
    private val jdbcClient: JdbcClient,
    private val postLock: PostLock,
) {
    /** 지운 글과 댓글(함께 지워진 답글 포함)의 수를 돌려준다. 트랜잭션 안에서만 부른다. */
    fun removeAll(
        authorId: Long,
        now: Instant,
    ): RemovedContent {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "탈퇴한 회원의 글과 댓글은 트랜잭션 안에서만 지울 수 있습니다(authorId=$authorId)."
        }
        val at = Timestamp.from(now)
        jdbcClient
            .sql("select pg_advisory_xact_lock(:namespace, 0)")
            .param("namespace", LOCK_NAMESPACE)
            .query(RowCallbackHandler { })
        val comments = removeComments(authorId, at)
        val postIds = removePosts(authorId, at)
        // 몬스터를 만드는 쪽이 같은 잠금 안에서 글이 살아 있는지 본다. 지운 글에 몬스터가 새로 생기지 않는다
        postLock.lockAll(postIds)
        return RemovedContent(posts = postIds.size, comments = comments)
    }

    /**
     * 내 살아 있는 댓글과, 내 원 댓글 아래 다른 회원의 살아 있는 답글을 지우고, 글마다 지운 수만큼 댓글 수를 줄인다.
     * 한 문장이라 세 갱신이 같은 스냅숏을 본다. 두 갱신이 잡는 행은 글쓴이로 갈려 겹치지 않는다. 답글은 "내 원 댓글 전부"
     * 아래에서 찾는다. 이번에 지운 것만 보면, 지우는 사이에 달린 답글이 다시 불렀을 때 남는다.
     */
    private fun removeComments(
        authorId: Long,
        at: Timestamp,
    ): Int =
        jdbcClient
            .sql(
                """
                with mine as (
                  update comments set deleted_at = :at, updated_at = :at
                  where author_id = :authorId and deleted_at is null
                  returning post_id
                ), replies as (
                  update comments c set deleted_at = :at, updated_at = :at
                  where c.deleted_at is null and c.author_id <> :authorId
                    and c.parent_id in (
                      select r.id from comments r where r.author_id = :authorId and r.parent_id is null
                    )
                  returning c.post_id
                ), gone as (
                  select post_id from mine union all select post_id from replies
                ), counted as (
                  select post_id, count(*) as removed from gone group by post_id
                ), adjusted as (
                  update posts p set comment_count = greatest(p.comment_count - counted.removed, 0)
                  from counted
                  where p.id = counted.post_id and p.deleted_at is null
                )
                select coalesce(sum(removed), 0) from counted
                """.trimIndent(),
            ).param("authorId", authorId)
            .param("at", at)
            .query(Int::class.java)
            .single()

    private fun removePosts(
        authorId: Long,
        at: Timestamp,
    ): List<Long> =
        jdbcClient
            .sql(
                """
                update posts set deleted_at = :at, updated_at = :at
                where author_id = :authorId and deleted_at is null
                returning id
                """.trimIndent(),
            ).param("authorId", authorId)
            .param("at", at)
            .query(Long::class.java)
            .list()
            .filterNotNull()

    private companion object {
        /** advisory lock 두 정수 키 중 첫째("wdrw"). 글 잠금("plck"), 작성 제한("post")과 키 공간이 겹치지 않는다. */
        const val LOCK_NAMESPACE = 0x77647277
    }
}

data class RemovedContent(
    val posts: Int,
    val comments: Int,
)
