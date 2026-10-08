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
    /**
     * 다른 회원에게 보이는 댓글: 댓글과 그 글을 지우지 않았고 숨기지 않았다(005 research R5). 지운 글의 댓글은 남아 있어도
     * 없는 것으로 본다. 공감처럼 남의 댓글에 하는 행동이 쓴다.
     */
    @Query(
        """
        select c from Comment c
        where c.id = :id and c.deletedAt is null and c.hiddenAt is null
          and exists (select 1 from Post p where p.id = c.postId and p.deletedAt is null and p.hiddenAt is null)
        """,
    )
    fun findVisible(
        @Param("id") id: Long,
    ): Comment?

    /**
     * [viewerId]에게 보이는 댓글: 지우지 않았고, 댓글과 그 글이 숨겨지지 않았거나 자기 것이다. 자기 댓글의 삭제가 쓴다.
     */
    @Query(
        """
        select c from Comment c
        where c.id = :id and c.deletedAt is null and (c.hiddenAt is null or c.authorId = :viewerId)
          and exists (
              select 1 from Post p
              where p.id = c.postId and p.deletedAt is null and (p.hiddenAt is null or p.authorId = :viewerId)
          )
        """,
    )
    fun findOwnedOrVisible(
        @Param("id") id: Long,
        @Param("viewerId") viewerId: Long,
    ): Comment?

    /**
     * 수정할 댓글을 행 잠금과 함께 읽는다. 겹친 삭제(답글 일괄 삭제 포함)가 먼저 커밋했으면 다시 평가해 null이 된다.
     * 지운 글의 댓글도 null이다([findOwnedOrVisible]과 같은 조건).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select c from Comment c
        where c.id = :id and c.deletedAt is null and (c.hiddenAt is null or c.authorId = :viewerId)
          and exists (
              select 1 from Post p
              where p.id = c.postId and p.deletedAt is null and (p.hiddenAt is null or p.authorId = :viewerId)
          )
        """,
    )
    fun findOwnedOrVisibleForUpdate(
        @Param("id") id: Long,
        @Param("viewerId") viewerId: Long,
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
     * 숨긴 댓글에는 새 답글을 받지 않는다(005 research R5).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Comment c where c.id = :id and c.deletedAt is null and c.hiddenAt is null")
    fun findLiveForReply(
        @Param("id") id: Long,
    ): Comment?

    /** 원 댓글의 살아 있는 답글 ID. 원 댓글을 지우기 직전에 읽어 답글마다 삭제 이벤트를 낸다. */
    @Query("select c.id from Comment c where c.parentId = :parentId and c.deletedAt is null")
    fun findLiveReplyIds(
        @Param("parentId") parentId: Long,
    ): List<Long>

    /** 숨김과 단계 열만 DB에서 다시 읽는다. 같은 트랜잭션에서 safety가 JdbcClient로 바꾼 값을 엔티티가 모르기 때문이다. */
    @Query(
        value =
            "select hidden_at is not null as hidden, risk_level as riskLevel, " +
                "review_requested_at is not null as reviewRequested from comments where id = :id",
        nativeQuery = true,
    )
    fun safetyStateOf(
        @Param("id") id: Long,
    ): CommentSafetyState

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
