package com.ogu.post.domain

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CommentRepository : JpaRepository<Comment, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): Comment?

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

    /** 원 댓글을 오래된 순으로 [afterId] 다음부터 읽는다(키셋, comments_post_parent_id_idx). */
    fun findByPostIdAndParentIdIsNullAndDeletedAtIsNullAndIdGreaterThanOrderByIdAsc(
        postId: Long,
        afterId: Long,
        limit: Limit,
    ): List<Comment>

    fun findByParentIdInAndDeletedAtIsNullOrderByIdAsc(parentIds: Collection<Long>): List<Comment>
}
