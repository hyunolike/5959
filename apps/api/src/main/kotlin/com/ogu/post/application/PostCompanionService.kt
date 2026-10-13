package com.ogu.post.application

import com.ogu.post.CommentTone
import com.ogu.post.CompanionComment
import com.ogu.post.CompanionReactionCheck
import com.ogu.post.CompanionReactionOutcome
import com.ogu.post.CompanionReactionResult
import com.ogu.post.CompanionTarget
import com.ogu.post.PostCompanionApi
import com.ogu.post.domain.Visibility
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [PostCompanionApi]의 구현(009 research R5). 댓글과 공감은 사람이 쓰는 [CommentService.write]와
 * [LikeService.likePost]를 그대로 부른다. 여기서 더하는 것은 "남기기 전에 다시 보기"뿐이라 규칙이 두 군데로 갈라지지
 * 않는다.
 */
@Service
class PostCompanionService(
    private val jdbcClient: JdbcClient,
    private val comments: CommentService,
    private val likes: LikeService,
) : PostCompanionApi {
    @Transactional(readOnly = true)
    override fun target(
        postId: Long,
        companionIds: Collection<Long>,
    ): CompanionTarget? {
        val ids = idsOf(companionIds)
        return jdbcClient
            .sql(
                """
                select p.id, p.author_id, p.content, p.comment_tone, p.created_at, p.updated_at,
                       p.hidden_at is not null as hidden,
                       exists ($OTHER_COMMENT) as other_commented
                from posts p
                where p.id = :postId and ${Visibility.notDeleted("p")}
                """.trimIndent(),
            ).param("postId", postId)
            .param("companionIds", ids)
            .query { rs, _ ->
                CompanionTarget(
                    postId = rs.getLong("id"),
                    authorId = rs.getLong("author_id"),
                    content = rs.getString("content"),
                    commentTone = CommentTone.valueOf(rs.getString("comment_tone")),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    contentVersion = rs.getTimestamp("updated_at").toInstant(),
                    hidden = rs.getBoolean("hidden"),
                    otherCommented = rs.getBoolean("other_commented"),
                )
            }.optional()
            .orElse(null)
            ?.copy(companionComments = companionComments(postId, ids))
    }

    /** AI 친구가 이 글에 남긴, 지우지 않은 댓글. 회원마다 가장 먼저 남긴 것 하나를 먼저 남긴 순으로 준다. */
    private fun companionComments(
        postId: Long,
        companionIds: Set<Long>,
    ): List<CompanionComment> =
        jdbcClient
            .sql(
                """
                select distinct on (c.author_id) c.author_id, c.id, c.content, c.hidden_at is null as visible
                from comments c
                where c.post_id = :postId and c.author_id in (:companionIds) and ${Visibility.notDeleted("c")}
                order by c.author_id, c.id
                """.trimIndent(),
            ).param("postId", postId)
            .param("companionIds", companionIds)
            .query { rs, _ ->
                CompanionComment(
                    memberId = rs.getLong("author_id"),
                    commentId = rs.getLong("id"),
                    content = rs.getString("content"),
                    visible = rs.getBoolean("visible"),
                )
            }.list()
            .sortedBy { it.commentId }

    @Transactional
    override fun react(
        postId: Long,
        memberId: Long,
        content: String,
        companionIds: Collection<Long>,
        check: CompanionReactionCheck,
    ): CompanionReactionResult {
        require(memberId in companionIds) { "AI 친구가 아닌 회원의 이름으로는 남기지 않는다" }
        // 글의 행을 먼저 잠근다(잠금 순서: 행 먼저, 글 잠금은 댓글과 공감의 리스너가 그다음에 잡는다). 같은 글에 겹쳐 온
        // 요청이 여기서 줄을 서고, 뒤에 온 쪽은 앞의 댓글을 보고 물러난다.
        val state = if (lockVisible(postId)) target(postId, companionIds) else null
        require(state?.authorId != memberId) { "글쓴이의 이름으로는 자기 글에 반응을 남기지 않는다" }
        val mine = state?.companionComments?.firstOrNull { it.memberId == memberId }
        return when {
            state == null -> CompanionReactionResult(CompanionReactionOutcome.GONE)
            mine != null -> CompanionReactionResult(CompanionReactionOutcome.ALREADY_REACTED, mine.commentId)
            // 정해진 수를 잠금 안에서 다시 본다. 맡은 시간이 지나 두 실행기가 함께 남겨도 넘지 않는다(012 FR-005)
            state.companionComments.size >= check.limit -> CompanionReactionResult(CompanionReactionOutcome.FULL)
            state.otherCommented -> CompanionReactionResult(CompanionReactionOutcome.HUMAN_COMMENTED)
            // 행을 잠근 뒤라 이 뒤로는 본문이 바뀌지 않는다
            check.contentVersion != null && !state.contentVersion.sameMicros(check.contentVersion) ->
                CompanionReactionResult(CompanionReactionOutcome.STALE)
            else -> leave(postId, memberId, content)
        }
    }

    private fun leave(
        postId: Long,
        memberId: Long,
        content: String,
    ): CompanionReactionResult {
        val comment = comments.write(postId, memberId, content, parentId = null)
        likes.likePost(postId, memberId)
        return CompanionReactionResult(CompanionReactionOutcome.REACTED, comment.commentId)
    }

    private fun lockVisible(postId: Long): Boolean =
        jdbcClient
            // 키를 바꾸지 않으므로 댓글과 공감이 글을 참조하며 잡는 잠금과 부딪히지 않는 약한 잠금으로 충분하다
            .sql(
                """
                select id from posts
                where id = :postId and ${Visibility.VISIBLE}
                for no key update
                """.trimIndent(),
            ).param("postId", postId)
            .query(Long::class.java)
            .optional()
            .isPresent

    /** `in ()`는 SQL이 아니다. 친구가 없으면 있을 수 없는 번호 하나를 넣는다. */
    private fun idsOf(companionIds: Collection<Long>): Set<Long> = companionIds.toSet().ifEmpty { setOf(NOBODY) }

    private fun Instant.sameMicros(other: Instant): Boolean = micros() == other.micros()

    private fun Instant.micros(): Instant = truncatedTo(ChronoUnit.MICROS)

    private companion object {
        const val NOBODY = -1L

        /** 글쓴이도 AI 친구도 아닌 회원의 보이는 댓글. 달았다가 지운 댓글은 글쓴이에게 남은 반응이 아니다. */
        val OTHER_COMMENT =
            """
            select 1 from comments c
            where c.post_id = p.id and c.author_id <> p.author_id and c.author_id not in (:companionIds)
              and ${Visibility.visible("c")}
            """.trimIndent()
    }
}
