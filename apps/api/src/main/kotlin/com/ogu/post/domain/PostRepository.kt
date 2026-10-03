package com.ogu.post.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface PostRepository : JpaRepository<Post, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): Post?

    /** 작성 제한(research R9)용. 지운 글도 센다(FR-018). */
    fun countByAuthorIdAndCreatedAtAfter(
        authorId: Long,
        since: Instant,
    ): Long

    fun findFirstByAuthorIdAndCreatedAtAfterOrderByCreatedAtAsc(
        authorId: Long,
        since: Instant,
    ): Post?

    /** 댓글 수를 한 문장으로 바꾼다. 동시에 단 댓글끼리 서로의 증가를 덮어쓰지 않는다. */
    @Modifying
    @Query(value = "update posts set comment_count = comment_count + :delta where id = :id", nativeQuery = true)
    fun addCommentCount(
        @Param("id") id: Long,
        @Param("delta") delta: Int,
    ): Int
}
