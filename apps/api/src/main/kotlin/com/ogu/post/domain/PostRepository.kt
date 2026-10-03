package com.ogu.post.domain

import org.springframework.data.jpa.repository.JpaRepository
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
}
