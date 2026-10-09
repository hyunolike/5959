package com.ogu.post.application

import com.ogu.post.CommentLiked
import com.ogu.post.PostLiked
import com.ogu.post.domain.Comment
import com.ogu.post.domain.CommentLikeRepository
import com.ogu.post.domain.CommentRepository
import com.ogu.post.domain.Post
import com.ogu.post.domain.PostLikeRepository
import com.ogu.post.domain.PostRepository
import com.ogu.post.presentation.dto.LikeResultResponse
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 글 공감과 댓글 공감(FR-007, US3-AC1, AC4, AC6, AC8). 공감하면 같은 트랜잭션에서 [PostLiked]나 [CommentLiked]를 발행하고,
 * monster 모듈이 같은 트랜잭션 안에서 HP를 줄인다(research R5). 취소는 공감 수만 줄이고 HP는 돌려주지 않는다.
 * 공감 수는 원자적 UPDATE로 바꾼다([PostLikeRepository], [CommentLikeRepository]).
 */
@Service
class LikeService(
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val postLikes: PostLikeRepository,
    private val commentLikes: CommentLikeRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** 지운 글이면 404, 자기 글이면 403 CANNOT_LIKE_OWN_POST, 이미 공감했으면(동시 요청 포함) 409 ALREADY_LIKED. */
    @Transactional
    fun likePost(
        postId: Long,
        memberId: Long,
    ): LikeResultResponse {
        val post = livePost(postId)
        if (post.authorId == memberId) throw BusinessException(ErrorCode.CANNOT_LIKE_OWN_POST)
        val likeCount = postLikes.add(postId, memberId, now()) ?: throw BusinessException(ErrorCode.ALREADY_LIKED)
        events.publishEvent(PostLiked(postId, memberId))
        return LikeResultResponse(likeCount, likedByMe = true)
    }

    /** 공감하지 않은 상태여도 200이다(계약). */
    @Transactional
    fun unlikePost(
        postId: Long,
        memberId: Long,
    ): LikeResultResponse {
        val post = livePost(postId)
        val likeCount = postLikes.remove(postId, memberId) ?: post.likeCount
        return LikeResultResponse(likeCount, likedByMe = false)
    }

    /** 지운 댓글이나 지운 글의 댓글이면 404 COMMENT_NOT_FOUND, 이미 공감했으면 409 ALREADY_LIKED. */
    @Transactional
    fun likeComment(
        commentId: Long,
        memberId: Long,
    ): LikeResultResponse {
        val comment = liveComment(commentId)
        val likeCount =
            commentLikes.add(commentId, memberId, now()) ?: throw BusinessException(ErrorCode.ALREADY_LIKED)
        events.publishEvent(CommentLiked(comment.postId, commentId, memberId))
        return LikeResultResponse(likeCount, likedByMe = true)
    }

    @Transactional
    fun unlikeComment(
        commentId: Long,
        memberId: Long,
    ): LikeResultResponse {
        val comment = liveComment(commentId)
        val likeCount = commentLikes.remove(commentId, memberId) ?: comment.likeCount
        return LikeResultResponse(likeCount, likedByMe = false)
    }

    private fun livePost(postId: Long): Post {
        val post = postRepository.findVisible(postId)
        return post ?: throw BusinessException(ErrorCode.POST_NOT_FOUND)
    }

    private fun liveComment(commentId: Long): Comment {
        val comment = commentRepository.findVisible(commentId)
        return comment ?: throw BusinessException(ErrorCode.COMMENT_NOT_FOUND)
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
