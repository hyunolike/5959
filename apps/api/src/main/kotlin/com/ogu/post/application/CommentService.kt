package com.ogu.post.application

import com.ogu.member.MemberApi
import com.ogu.member.MemberInfo
import com.ogu.post.CommentCreated
import com.ogu.post.CommentRemoved
import com.ogu.post.CommentWritten
import com.ogu.post.ContentSafety
import com.ogu.post.domain.Comment
import com.ogu.post.domain.CommentLikeRepository
import com.ogu.post.domain.CommentRepository
import com.ogu.post.domain.CommentSafetyState
import com.ogu.post.domain.PostRepository
import com.ogu.post.presentation.dto.CommentAuthorResponse
import com.ogu.post.presentation.dto.CommentPageResponse
import com.ogu.post.presentation.dto.CommentResponse
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 댓글과 답글(FR-008, FR-012, US3-AC2, AC3). 작성하면 같은 트랜잭션에서 [CommentCreated]를 발행하고 monster 모듈이
 * 회원당 글 하나에 한 번만 HP를 줄인다(research R5). 목록은 원 댓글을 오래된 순으로 [PAGE_SIZE]개씩 키셋으로 읽고,
 * 그 답글과 공감 여부, 작성자를 한 번씩 일괄로 읽는다.
 */
@Service
class CommentService(
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val commentLikes: CommentLikeRepository,
    private val memberApi: MemberApi,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * 지운 글이면 404 POST_NOT_FOUND, 본문이 1~300자가 아니면 400 INVALID_REQUEST. 부모가 없거나 지웠거나 다른 글의
     * 댓글이면 404 COMMENT_NOT_FOUND, 부모가 답글이면 400 INVALID_PARENT_COMMENT.
     */
    @Transactional
    fun write(
        postId: Long,
        authorId: Long,
        content: String,
        parentId: Long?,
    ): CommentResponse {
        // 숨긴 글에는 작성자 자신도 댓글을 달 수 없다(다른 회원이 볼 수 없는 글에 대화가 이어지지 않게 한다, 005 research R5)
        postRepository.findVisible(postId) ?: throw BusinessException(ErrorCode.POST_NOT_FOUND)
        Comment.normalizeContent(content)
        val parent = parentId?.let { parentOf(postId, it) }
        val now = now()
        val comment = commentRepository.save(Comment.write(postId, authorId, parent, content, now))
        // 앞의 확인 뒤에 글이 지워졌으면 0행이다. 404로 끝내 방금 넣은 댓글도 함께 되돌린다.
        if (postRepository.addCommentCount(postId, 1) == 0) throw BusinessException(ErrorCode.POST_NOT_FOUND)
        events.publishEvent(CommentCreated(postId, comment.id, authorId))
        // 같은 트랜잭션에서 safety가 판정한다. 위기면 저장과 숨김이 함께 커밋된다(005 research R2)
        events.publishEvent(CommentWritten(postId, comment.id, authorId, edited = false))
        val author = memberApi.getMember(authorId)
        // 리스너가 숨김과 단계를 JdbcClient로 적었을 수 있어 그 열만 다시 읽는다
        val state = commentRepository.safetyStateOf(comment.id)
        return comment.toResponse(mapOf(authorId to author), authorId, liked = emptySet(), replies = emptyList(), state)
    }

    /** 원 댓글 [PAGE_SIZE]개와 그 답글. 지운 댓글과 지운 원 댓글의 답글은 보이지 않는다. 지운 글이면 404. */
    @Transactional(readOnly = true)
    fun list(
        postId: Long,
        viewerId: Long,
        cursor: String?,
    ): CommentPageResponse {
        // 작성과 같은 순서: 지운 글이면 커서가 틀려도 404다. 숨긴 글의 댓글은 글쓴이만 본다
        postRepository.findOwnedOrVisible(postId, viewerId) ?: throw BusinessException(ErrorCode.POST_NOT_FOUND)
        val afterId = cursor?.let(CommentCursor::decode) ?: 0L
        val rows =
            commentRepository.findByPostIdAndParentIdIsNullAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
                postId,
                afterId,
                Limit.of(PAGE_SIZE + 1),
            )
        val roots = rows.take(PAGE_SIZE)
        if (roots.isEmpty()) return CommentPageResponse(emptyList(), null)

        val replies = commentRepository.findByParentIdInAndDeletedAtIsNullOrderByIdAsc(roots.map { it.id })
        val all = roots + replies
        val liked = commentLikes.likedCommentIds(viewerId, all.map { it.id })
        val members = memberApi.getMembers(all.map { it.authorId }.toSet())
        val repliesByParent = replies.groupBy { it.parentId }
        val items =
            roots.map { root ->
                val children =
                    repliesByParent[root.id].orEmpty().map { it.toResponse(members, viewerId, liked, emptyList()) }
                root.toResponse(members, viewerId, liked, children)
            }
        val nextCursor = if (rows.size > PAGE_SIZE) CommentCursor.encode(roots.last().id) else null
        return CommentPageResponse(items, nextCursor)
    }

    /** 댓글 본문을 고친다(US4-AC3, FR-014). 규칙은 작성과 같다. 없거나 지웠거나 지운 글의 댓글이면 404, 남의 댓글이면 403. */
    @Transactional
    fun update(
        commentId: Long,
        memberId: Long,
        content: String,
    ) {
        // 행 잠금을 잡고 읽는다. 겹친 삭제가 먼저 커밋했으면 여기서 404가 된다.
        val comment =
            commentRepository.findOwnedOrVisibleForUpdate(commentId, memberId)
                ?: throw BusinessException(ErrorCode.COMMENT_NOT_FOUND)
        if (comment.authorId != memberId) throw BusinessException(ErrorCode.NOT_AUTHOR)
        comment.edit(content, now())
        // 리스너가 JdbcClient로 고친 본문을 읽으므로 먼저 내보낸다
        commentRepository.flush()
        events.publishEvent(CommentWritten(comment.postId, commentId, memberId, edited = true))
    }

    /**
     * 댓글을 지운다(US4-AC3, FR-014). 원 댓글이면 살아 있던 답글도 같은 트랜잭션에서 지우고, 댓글 수를 실제로 지운 개수만큼
     * 줄인다(data-model.md). HP는 돌려주지 않는다. 댓글 행을 먼저 잠그고 `posts` 행은 마지막에 잠근다(댓글 작성과 같은 순서).
     * 그사이 다른 요청이 먼저 지웠으면 404다.
     */
    @Transactional
    fun delete(
        commentId: Long,
        memberId: Long,
    ) {
        val comment = ownComment(commentId, memberId)
        val now = now()
        if (commentRepository.softDelete(commentId, now) == 0) throw BusinessException(ErrorCode.COMMENT_NOT_FOUND)
        val replyIds = if (comment.isReply) emptyList() else commentRepository.findLiveReplyIds(commentId)
        val replies = if (comment.isReply) 0 else commentRepository.softDeleteReplies(commentId, now)
        postRepository.addCommentCount(comment.postId, -(1 + replies))
        (listOf(commentId) + replyIds).forEach { events.publishEvent(CommentRemoved(it)) }
    }

    private fun ownComment(
        commentId: Long,
        memberId: Long,
    ): Comment {
        val comment =
            commentRepository.findOwnedOrVisible(commentId, memberId)
                ?: throw BusinessException(ErrorCode.COMMENT_NOT_FOUND)
        if (comment.authorId != memberId) throw BusinessException(ErrorCode.NOT_AUTHOR)
        return comment
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun parentOf(
        postId: Long,
        parentId: Long,
    ): Comment {
        val parent = commentRepository.findLiveForReply(parentId)
        if (parent == null || parent.postId != postId) throw BusinessException(ErrorCode.COMMENT_NOT_FOUND)
        if (parent.isReply) throw BusinessException(ErrorCode.INVALID_PARENT_COMMENT)
        return parent
    }

    private fun Comment.toResponse(
        members: Map<Long, MemberInfo>,
        viewerId: Long,
        liked: Set<Long>,
        replies: List<CommentResponse>,
        state: CommentSafetyState = CommentSafetyState(isHidden, riskLevel, reviewRequestedAt != null),
    ): CommentResponse {
        val member = members[authorId]
        val mine = authorId == viewerId
        // 숨긴 댓글은 다른 회원에게 자리만 보인다. 내용과 작성자를 싣지 않는다(005 US1-AC5)
        val concealed = state.hidden && !mine
        return CommentResponse(
            commentId = id,
            author =
                if (concealed) {
                    null
                } else {
                    CommentAuthorResponse(
                        id = authorId,
                        nickname = member?.nickname.orEmpty(),
                        jobRole = member?.jobRole,
                        careerYear = member?.careerYear,
                    )
                },
            content = if (concealed) null else content,
            hidden = state.hidden,
            safety = if (mine) ContentSafety(state.riskLevel, state.hidden, state.reviewRequested) else null,
            likeCount = likeCount,
            likedByMe = id in liked,
            mine = authorId == viewerId,
            createdAt = createdAt,
            replies = replies,
        )
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
