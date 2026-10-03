package com.ogu.post.domain

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface CommentRepository : JpaRepository<Comment, Long> {
    /** 살아 있는 글의 살아 있는 댓글. 지운 글의 댓글은 남아 있어도 없는 것으로 본다. */
    @Query(
        """
        select c from Comment c
        where c.id = :id and c.deletedAt is null
          and exists (select 1 from Post p where p.id = c.postId and p.deletedAt is null)
        """,
    )
    fun findLive(
        @Param("id") id: Long,
    ): Comment?

    /**
     * 수정할 댓글을 행 잠금과 함께 읽는다. 겹친 삭제(답글 일괄 삭제 포함)가 먼저 커밋했으면 다시 평가해 null이 된다.
     * 지운 글의 댓글도 null이다([findLive]와 같은 조건).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select c from Comment c
        where c.id = :id and c.deletedAt is null
          and exists (select 1 from Post p where p.id = c.postId and p.deletedAt is null)
        """,
    )
    fun findLiveForUpdate(
        @Param("id") id: Long,
    ): Comment?

    /** 원 댓글을 오래된 순으로 [afterId] 다음부터 읽는다(키셋, comments_post_parent_id_idx). */
    fun findByPostIdAndParentIdIsNullAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
        postId: Long,
        afterId: Long,
        limit: Limit,
    ): List<Comment>

    fun findByParentIdInAndDeletedAtIsNullOrderByIdAsc(parentIds: Collection<Long>): List<Comment>

    /**
     * 답글을 달 원 댓글을 `FOR SHARE`로 읽는다. 같은 원 댓글을 지우는 트랜잭션과 줄을 서서, 지우는 중인 원 댓글에 답글이
     * 붙어 살아남거나(답글 일괄 삭제를 비껴감) 댓글 수가 어긋나지 않게 한다. 기다린 뒤 지워졌으면 null이다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Comment c where c.id = :id and c.deletedAt is null")
    fun findLiveForReply(
        @Param("id") id: Long,
    ): Comment?

    /** 살아 있는 댓글 하나를 지운다. 이미 지웠으면 0이다. */
    @Modifying
    @Query(
        value = "update comments set deleted_at = :now, updated_at = :now where id = :id and deleted_at is null",
        nativeQuery = true,
    )
    fun softDelete(
        @Param("id") id: Long,
        @Param("now") now: Instant,
    ): Int

    /** 원 댓글의 살아 있는 답글을 모두 지우고 지운 개수를 돌려준다(먼저 지운 답글은 세지 않는다). */
    @Modifying
    @Query(
        value =
            "update comments set deleted_at = :now, updated_at = :now " +
                "where parent_id = :parentId and deleted_at is null",
        nativeQuery = true,
    )
    fun softDeleteReplies(
        @Param("parentId") parentId: Long,
        @Param("now") now: Instant,
    ): Int
}
