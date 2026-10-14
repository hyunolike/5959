package com.ogu.post.application

import com.ogu.post.CommentRemoved
import com.ogu.post.PostRemoved
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 탈퇴한 회원의 글과 댓글의 본문을 저장소에서 지운다(013 FR-008, research R4). 커밋 뒤에, 트랜잭션 밖에서 불린다.
 * [BATCH_SIZE]개씩 맡아 묶음마다 따로 커밋하므로 글이 수천 개여도 긴 트랜잭션이 없다.
 *
 * 묶음 하나가 하는 일은 둘이다. 회원이 스스로 지울 때와 같은 이벤트([PostRemoved], [CommentRemoved])를 내고, 본문을 빈
 * 글자열로 바꾼다. 이벤트를 받는 쪽은 지금 그대로다. `safety`가 열린 신고와 재검토 요청을 닫고 `recommend`가 임베딩을 지운다.
 *
 * - 맡는 것은 지운 행뿐이다. 살아 있는 글의 본문을 비우는 일은 없다. 아직 비우지 않았다는 표지는 `content <> ''`다. 본문은 한 글자 이상이라 빈 본문은 비운 것뿐이다. 다시 불리면 남은 것만
 *   맡는다(US5-AC2). 이벤트가 다시 나가도 받는 쪽의 결과는 같다.
 * - 회원이 탈퇴 전에 스스로 지운 글과 댓글도 본문이 저장돼 있었으므로 함께 비운다.
 * - 내 원 댓글 아래 다른 회원의 답글은 함께 지워졌지만 본문은 그 회원의 것이라 비우지 않는다. 그 답글에 걸린 신고는
 *   닫혀야 하므로 [CommentRemoved]는 답글에도 낸다.
 * - 맨 먼저 [WithdrawnContentRemover.removeAll]을 한 번 더 부른다. 탈퇴와 겹쳐 들어온 글과 댓글이 있으면 여기서 지워진다.
 * - 같은 정리가 겹쳐 돌면 행 잠금에서 줄을 선다. 남은 것이 없을 때만 끝난다.
 *
 * 로그에는 회원 번호와 수만 남긴다.
 */
@Component
class WithdrawnContentCleaner(
    private val remover: WithdrawnContentRemover,
    private val jdbcClient: JdbcClient,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun clean(authorId: Long) {
        val late = transaction.execute { remover.removeAll(authorId, clock.instant().truncatedTo(ChronoUnit.MICROS)) }
        if (late != null && late.posts + late.comments > 0) {
            log.warn("탈퇴와 겹쳐 들어온 글과 댓글을 지웠습니다: authorId={}, posts={}, comments={}", authorId, late.posts, late.comments)
        }
        val posts = drain { scrubPosts(authorId) to hasContent("posts", authorId) }
        val comments = drain { scrubComments(authorId) to hasContent("comments", authorId) }
        log.info("탈퇴한 회원의 본문을 비웠습니다: authorId={}, posts={}, comments={}", authorId, posts, comments)
    }

    /** 묶음이 비고 남은 것도 없을 때까지 돈다. 묶음이 비었는데 남은 것이 있으면 다른 실행기가 잡고 있는 것이라 다시 기다린다. */
    private fun drain(step: () -> Pair<Int, Boolean>): Int {
        var total = 0
        while (true) {
            val (scrubbed, remaining) = step()
            total += scrubbed
            if (scrubbed == 0 && !remaining) return total
        }
    }

    private fun scrubPosts(authorId: Long): Int =
        transaction.execute {
            val ids = claim("posts", authorId)
            ids.forEach { events.publishEvent(PostRemoved(it)) }
            erase("posts", ids)
        } ?: 0

    private fun scrubComments(authorId: Long): Int =
        transaction.execute {
            val ids = claim("comments", authorId)
            (ids + repliesUnder(ids)).forEach { events.publishEvent(CommentRemoved(it)) }
            erase("comments", ids)
        } ?: 0

    /** 본문이 남은 행을 번호 순으로 맡는다. 다른 실행기가 잡고 있으면 기다렸다가, 그사이 비워진 행은 빼고 받는다. */
    private fun claim(
        table: String,
        authorId: Long,
    ): List<Long> =
        jdbcClient
            .sql(
                """
                select id from $table
                where author_id = :authorId and deleted_at is not null and content <> ''
                order by id limit :limit for update
                """.trimIndent(),
            ).param("authorId", authorId)
            .param("limit", BATCH_SIZE)
            .query(Long::class.java)
            .list()
            .filterNotNull()

    private fun erase(
        table: String,
        ids: List<Long>,
    ): Int {
        if (ids.isEmpty()) return 0
        return jdbcClient.sql("update $table set content = '' where id in (:ids)").param("ids", ids).update()
    }

    /** [commentIds] 가운데 원 댓글 아래의 답글. 지운 것까지 모두다. 답글의 답글은 없으므로(1단계) 한 번만 찾는다. */
    private fun repliesUnder(commentIds: List<Long>): List<Long> {
        if (commentIds.isEmpty()) return emptyList()
        return jdbcClient
            .sql(
                """
                select reply.id
                from comments parent
                join comments reply on reply.post_id = parent.post_id and reply.parent_id = parent.id
                where parent.id in (:ids) and parent.parent_id is null
                """.trimIndent(),
            ).param("ids", commentIds)
            .query(Long::class.java)
            .list()
            .filterNotNull()
    }

    private fun hasContent(
        table: String,
        authorId: Long,
    ): Boolean =
        jdbcClient
            .sql(
                """
                select exists(
                  select 1 from $table where author_id = :authorId and deleted_at is not null and content <> ''
                )
                """.trimIndent(),
            ).param("authorId", authorId)
            .query(Boolean::class.java)
            .single()

    private companion object {
        const val BATCH_SIZE = 200
        val log = LoggerFactory.getLogger(WithdrawnContentCleaner::class.java)
    }
}
