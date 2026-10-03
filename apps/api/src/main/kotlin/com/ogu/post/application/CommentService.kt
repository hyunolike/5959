package com.ogu.post.application

import com.ogu.member.MemberApi
import com.ogu.member.MemberInfo
import com.ogu.post.CommentCreated
import com.ogu.post.domain.Comment
import com.ogu.post.domain.CommentLikeRepository
import com.ogu.post.domain.CommentRepository
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
        postRepository.findByIdAndDeletedAtIsNull(postId) ?: throw BusinessException(ErrorCode.POST_NOT_FOUND)
        Comment.normalizeContent(content)
        val parent = parentId?.let { parentOf(postId, it) }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val comment = commentRepository.save(Comment.write(postId, authorId, parent, content, now))
        postRepository.addCommentCount(postId, 1)
        events.publishEvent(CommentCreated(postId, comment.id, authorId))
        val author = memberApi.getMember(authorId)
        return comment.toResponse(mapOf(authorId to author), authorId, liked = emptySet(), replies = emptyList())
    }

    /** 원 댓글 [PAGE_SIZE]개와 그 답글. 지운 댓글과 지운 원 댓글의 답글은 보이지 않는다. 지운 글이면 404. */
    @Transactional(readOnly = true)
    fun list(
        postId: Long,
        viewerId: Long,
        cursor: String?,
    ): CommentPageResponse {
        // 작성과 같은 순서: 지운 글이면 커서가 틀려도 404다
        postRepository.findByIdAndDeletedAtIsNull(postId) ?: throw BusinessException(ErrorCode.POST_NOT_FOUND)
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

    private fun parentOf(
        postId: Long,
        parentId: Long,
    ): Comment {
        val parent = commentRepository.findByIdAndDeletedAtIsNull(parentId)
        if (parent == null || parent.postId != postId) throw BusinessException(ErrorCode.COMMENT_NOT_FOUND)
        if (parent.isReply) throw BusinessException(ErrorCode.INVALID_PARENT_COMMENT)
        return parent
    }

    private fun Comment.toResponse(
        members: Map<Long, MemberInfo>,
        viewerId: Long,
        liked: Set<Long>,
        replies: List<CommentResponse>,
    ): CommentResponse {
        val member = members[authorId]
        return CommentResponse(
            commentId = id,
            author =
                CommentAuthorResponse(
                    id = authorId,
                    nickname = member?.nickname.orEmpty(),
                    jobRole = member?.jobRole,
                    careerYear = member?.careerYear,
                ),
            content = content,
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
