package com.ogu.post.application

import com.ogu.post.CommentTone
import com.ogu.post.CompanionReactionOutcome
import com.ogu.post.CompanionReactionResult
import com.ogu.post.CompanionTarget
import com.ogu.post.PostCompanionApi
import com.ogu.post.domain.Visibility
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
    ): CompanionTarget? =
        jdbcClient
            .sql(
                """
                select p.id, p.author_id, p.content, p.comment_tone, p.created_at, p.hidden_at is not null as hidden,
                       ($COMPANION_COMMENT) as companion_comment_id,
                       exists ($OTHER_COMMENT) as other_commented
                from posts p
                where p.id = :postId and ${Visibility.notDeleted("p")}
                """.trimIndent(),
            ).param("postId", postId)
            .param("companionIds", idsOf(companionIds))
            .query { rs, _ ->
                CompanionTarget(
                    postId = rs.getLong("id"),
                    authorId = rs.getLong("author_id"),
                    content = rs.getString("content"),
                    commentTone = CommentTone.valueOf(rs.getString("comment_tone")),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    hidden = rs.getBoolean("hidden"),
                    companionCommentId = rs.getObject("companion_comment_id")?.let { (it as Number).toLong() },
                    otherCommented = rs.getBoolean("other_commented"),
                )
            }.optional()
            .orElse(null)

    @Transactional
    override fun react(
        postId: Long,
        memberId: Long,
        content: String,
        companionIds: Collection<Long>,
    ): CompanionReactionResult {
        // 글의 행을 먼저 잠근다(잠금 순서: 행 먼저, 글 잠금은 댓글과 공감의 리스너가 그다음에 잡는다). 같은 글에 겹쳐 온
        // 요청이 여기서 줄을 서고, 뒤에 온 쪽은 앞의 댓글을 보고 물러난다.
        if (!lockVisible(postId)) return CompanionReactionResult(CompanionReactionOutcome.GONE)
        val state = target(postId, companionIds) ?: return CompanionReactionResult(CompanionReactionOutcome.GONE)
        state.companionCommentId?.let {
            return CompanionReactionResult(CompanionReactionOutcome.ALREADY_REACTED, it)
        }
        if (state.otherCommented) return CompanionReactionResult(CompanionReactionOutcome.HUMAN_COMMENTED)

        val comment = comments.write(postId, memberId, content, parentId = null)
        likes.likePost(postId, memberId)
        return CompanionReactionResult(CompanionReactionOutcome.REACTED, comment.commentId)
    }

    private fun lockVisible(postId: Long): Boolean =
        jdbcClient
            .sql("select id from posts where id = :postId and ${Visibility.VISIBLE} for update")
            .param("postId", postId)
            .query(Long::class.java)
            .optional()
            .isPresent

    /** `in ()`는 SQL이 아니다. 친구가 없으면 있을 수 없는 번호 하나를 넣는다. */
    private fun idsOf(companionIds: Collection<Long>): Set<Long> = companionIds.toSet().ifEmpty { setOf(NOBODY) }

    private companion object {
        const val NOBODY = -1L

        /** AI 친구가 이 글에 남긴, 지우지 않은 댓글 가운데 가장 먼저 것. 숨겨진 것도 센다(다시 남기지 않는다). */
        val COMPANION_COMMENT =
            """
            select min(c.id) from comments c
            where c.post_id = p.id and c.author_id in (:companionIds) and ${Visibility.notDeleted("c")}
            """.trimIndent()

        /** 글쓴이도 AI 친구도 아닌 회원의 보이는 댓글. 달았다가 지운 댓글은 글쓴이에게 남은 반응이 아니다. */
        val OTHER_COMMENT =
            """
            select 1 from comments c
            where c.post_id = p.id and c.author_id <> p.author_id and c.author_id not in (:companionIds)
              and ${Visibility.visible("c")}
            """.trimIndent()
    }
}
